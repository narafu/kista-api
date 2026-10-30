package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.trading.application.event.TradingErrorEvent;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.matching.domain.model.VrPosition;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 증권사 접수: 잔류 SUBMITTING 대사 → BUY 가격 보정 → PLANNED 건별 선점·접수(OrderSubmitter) — 결과 불명 주문은 SUBMITTING으로 남아 재접수되지 않는다
@Component
@RequiredArgsConstructor
@Slf4j
class TradingOrderExecutor {

    private final OrderPort orderPort;
    private final BuyOrderPriceCapper buyOrderPriceCapper;
    private final CycleOrderStrategies cycleOrderStrategies;
    private final StrategyLedgerBudget ledgerBudget;            // 재캡이 BUY 총액을 늘릴 때만 사이클 원장 잔여 조회
    private final AccountBudgetLock accountBudgetLock;          // 계좌별 재캡 예산 산정 직렬화 — 후보 계획 승인+저장과 같은 락
    private final OrderSubmitter orderSubmitter;                // 건별 선점 → 접수 → PLACED/FAILED 기록
    private final OrderSubmissionReconciler reconciler;         // 접수 전 잔류 SUBMITTING 대사(Toss 멱등 재요청·알림)
    private final ApplicationEventPublisher eventPublisher;     // 잔고 부족 거절 사용자 알림(InsufficientBalanceEvent)
    private final DcaExecutionRecorder dcaRecorder;             // 회차 판정 전략(DCA)은 알림 대신 회차 skip 기록

    // AT_OPEN PLANNED 주문 접수 — 개장 스케쥴러 선접수 + 개장 후 수동실행 공용
    // BUY cap 보정을 AT_OPEN 스코프(BuyOrderPriceCapper.capIfNeeded(mode, atOpen=true, ...))로 적용한 뒤
    // AT_OPEN PLANNED만 재조회해 접수한다 — 동일 사이클에 공존 가능한 AT_CLOSE PLANNED(미도래)는 건드리지 않는다
    // (findPlannedByCycleAndDate를 그대로 쓰면 접수 전 AT_CLOSE 주문까지 캡 재산정 대상이 되는 버그가 발생함)
    List<Order> placeAtOpenOrders(LocalDate tradeDate, TradingAccount account, StrategyCycle cycle,
                                  BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        reconciler.reconcile(cycle.id(), tradeDate, account);
        applyCap(true, tradeDate, account, cycle, currentPrice, position, vrPosition, strategy);
        List<Order> atOpenOrders = orderPort.findAtOpenPlannedByCycleAndDate(cycle.id(), tradeDate);
        if (atOpenOrders.isEmpty()) {
            log.info("[{}] 개장 선접수할 주문 없음", account.nickname());
            return List.of();
        }
        PlaceResult result = placeEach(atOpenOrders, account);
        reportInsufficient(result, tradeDate, account, strategy);
        List<Order> placed = result.placed();
        log.info("[{}] 개장 주문 {}건 선접수 (성공/{} 시도)", account.nickname(), placed.size(), atOpenOrders.size());
        return placed;
    }

    // BuyOrderPriceCapper.capIfNeeded 적용 여부는 전략의 needsCapCheck()로 결정 — position/vrPosition
    // null 가드는 applyCap에 있다 (capIfNeeded는 @Transactional이라 내부에 두면 skip 케이스마다 빈 트랜잭션이 열림)
    List<Order> placeOrders(LocalDate today, TradingAccount account, StrategyCycle cycle,
                            BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        reconciler.reconcile(cycle.id(), today, account);
        applyCap(false, today, account, cycle, currentPrice, position, vrPosition, strategy);
        List<Order> planned = orderPort.findPlannedByCycleAndDate(cycle.id(), today);
        PlaceResult result = placeEach(planned, account);
        reportInsufficient(result, today, account, strategy);
        List<Order> placed = result.placed();
        log.info("[{}] 주문 {}건 접수 (성공/{} 시도)", account.nickname(), placed.size(), planned.size());
        return placed;
    }

    // AT_OPEN/AT_CLOSE 공용 BUY 가격 캡 디스패치 — needsCapCheck() skip 가드를 여기서 걸어
    // BuyOrderPriceCapper.capIfNeeded(@Transactional)가 skip 케이스에 호출되지 않도록 한다(빈 트랜잭션 오픈 방지)
    private void applyCap(boolean atOpen, LocalDate date, TradingAccount account, StrategyCycle cycle,
                          BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        try {
            capWithBudget(atOpen, date, account, cycle, currentPrice, position, vrPosition, strategy);
        } catch (OrderStateConflictException | PessimisticLockingFailureException e) {
            // 재캡 대상 BUY가 그 사이 사용자 취소·정리됨(또는 일시중지 일괄 삭제와 행 잠금 데드락) —
            // 재캡 트랜잭션은 통째로 롤백, 남은 PLANNED를 그대로 접수한다
            log.warn("[{}] BUY 재캡 중 주문 상태 경합 — 재캡 생략: {}", account.nickname(), e.getMessage());
        }
    }

    private void capWithBudget(boolean atOpen, LocalDate date, TradingAccount account, StrategyCycle cycle,
                               BigDecimal currentPrice, InfinitePosition position, VrPosition vrPosition, Strategy strategy) {
        if (currentPrice == null) return;
        CycleOrderStrategy orderStrategy = cycleOrderStrategies.of(strategy.type());
        if (!orderStrategy.needsCapCheck(position, vrPosition)) return;
        if (!buyOrderPriceCapper.capIfNeeded(strategy.type(), atOpen, date, account, cycle.id(),
                currentPrice, position, vrPosition, strategy.ticker(), null)) return;

        // 재캡이 BUY 총액을 늘림 — 사이클 원장 잔여(같은 날 PLANNED·SUBMITTING·PLACED BUY 차감 후)를 예산으로 재시도한다.
        // 자기 스코프 원본 PLANNED BUY는 remaining이 이미 뺐으므로 capper가 되돌려 더한다.
        // 같은 계좌의 "원장 조회 → 재캡 커밋"을 직렬화해 동시 재캡끼리 같은 여유분을 이중 사용하지 않게 한다(접수는 락 밖 — 병렬 유지)
        try {
            accountBudgetLock.call(account.id(), () -> {
                return buyOrderPriceCapper.capIfNeeded(strategy.type(), atOpen, date, account, cycle.id(),
                        currentPrice, position, vrPosition, strategy.ticker(), ledgerBudget.remaining(cycle, date));
            });
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt(); // placeEach 상단 체크가 남은 접수를 중단
        }
    }

    // 주문 목록을 개별 접수 — 건별로 선점(SUBMITTING) 후 접수, 실패·선점 경합 주문은 건너뛰고 다음 주문 계속
    // 계좌(앱키) 단위 호출 간격 게이트는 KisHttpClient.executeWithRetry가 전담(경로: place() → KisOrderApi → KisHttpClient.post()) — 여기서 별도 페이싱 불필요
    private PlaceResult placeEach(List<Order> orders, TradingAccount account) {
        List<Order> placed = new ArrayList<>();
        List<Order> insufficient = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++) {
            // 주문 간격이 이미 충분히 벌어져(정상적인 왕복 지연) 게이트가 대기 없이 즉시 반환하는 경우
            // 아래 체크만으로는 놓친다 — 매 주문 시도 전에 별도로 확인한다
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[{}] 인터럽트 감지 — 남은 주문 {}건 접수 중단", account.nickname(), orders.size() - i);
                break;
            }
            Order p = orders.get(i);
            OrderSubmitter.Submission submission = orderSubmitter.submit(p, account.brokerRef(), account.nickname());
            if (submission.outcome() == OrderSubmitter.Outcome.PLACED) {
                placed.add(p.withPlaced(submission.externalOrderId()));
            } else if (submission.outcome() == OrderSubmitter.Outcome.INSUFFICIENT_FUNDS) {
                insufficient.add(p);
            }
            // KisHttpClient의 호출 간격 게이트가 대기 중 인터럽트를 감지하면 확정 거절로 전파한다 — 이 주문만
            // FAILED로 남기고 나머지 주문은 중단한다
            if (Thread.currentThread().isInterrupted()) {
                log.warn("[{}] 인터럽트 감지 — 남은 주문 접수 중단", account.nickname());
                break;
            }
        }
        return new PlaceResult(placed, insufficient);
    }

    // 접수 결과 — placed: 접수 성공, insufficient: 증권사 잔고·수량 부족으로 거절된 주문(BUY·SELL 혼재 가능)
    private record PlaceResult(List<Order> placed, List<Order> insufficient) {}

    private static final String DCA_LEG_PREFIX = "DCA_"; // DCA 주문 leg = "DCA_" + ruleKey

    // 잔고·수량 부족 거절을 사용자 알림으로 전달 — 전략·방향당 1건(DCA는 회차 skip 기록), 알림 실패가 접수 결과를 막지 않게 격리
    private void reportInsufficient(PlaceResult result, LocalDate tradeDate, TradingAccount account, Strategy strategy) {
        if (result.insufficient().isEmpty()) return;
        try {
            if (cycleOrderStrategies.of(strategy.type()).recordsRounds()) {
                for (Order order : result.insufficient()) {
                    if (!order.orderLeg().startsWith(DCA_LEG_PREFIX)) continue;
                    boolean sell = order.direction() == OrderDirection.SELL;
                    dcaRecorder.recordOrderRejected(strategy, account, tradeDate, order.orderLeg().substring(DCA_LEG_PREFIX.length()),
                            order, sell ? DcaSkipReason.INSUFFICIENT_HOLDINGS : DcaSkipReason.INSUFFICIENT_BALANCE,
                            sell ? "증권사 판매 가능 수량 부족" : "증권사 주문 가능 금액 부족");
                }
                return;
            }
            // 방향별로 1건씩 — 일부 접수 여부도 같은 방향의 접수 주문이 있을 때만
            for (OrderDirection direction : OrderDirection.values()) {
                int failed = (int) result.insufficient().stream().filter(o -> o.direction() == direction).count();
                if (failed == 0) continue;
                eventPublisher.publishEvent(new InsufficientBalanceEvent(account.userId(), account.id(), account.nickname(),
                        0, null, strategy.ticker(), strategy.type(), failed,
                        result.placed().stream().anyMatch(o -> o.direction() == direction), direction));
            }
        } catch (Exception e) {
            // 관리자 이벤트는 이미 억제됐으므로 여기서 실패하면 아무도 모른다 — 관리자에게 알린다
            log.error("[{}] 잔고 부족 알림 처리 실패: {}", account.nickname(), e.getMessage());
            eventPublisher.publishEvent(new TradingErrorEvent(null, "[잔고 부족 알림 실패] %s %s %s — %s"
                    .formatted(account.nickname(), strategy.ticker(), strategy.type(), e.getMessage())));
        }
    }
}
