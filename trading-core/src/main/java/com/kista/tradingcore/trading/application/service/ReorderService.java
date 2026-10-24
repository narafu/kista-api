package com.kista.tradingcore.trading.application.service;
import com.kista.tradingcore.trading.application.service.support.SelectionChain;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.ManualTradingException;
import com.kista.tradingcore.trading.domain.model.OrderStateConflictException;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.ReorderCommand;
import com.kista.tradingcore.trading.domain.model.ReorderResult;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.application.usecase.ReorderUseCase;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.broker.domain.model.CancelInstruction;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.broker.application.port.output.BrokerOrderCorrectionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
// 비-트랜잭션 — 증권사 취소·접수 HTTP를 트랜잭션 밖에서 실행하고 DB 쓰기는 건별 짧은 트랜잭션(OrderCancelStateWriter·saveAll)으로 처리
class ReorderService implements ReorderUseCase {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;
    private final StrategyCyclePort strategyCyclePort;
    private final OrderPort orderPort;
    private final BrokerOrderCorrectionPort brokerOrderCorrectionPort;
    private final MarketCalendarPort marketCalendarPort;
    private final OrderCancelStateWriter stateWriter; // 원본 취소 DB 기록 — 짧은 트랜잭션
    private final OrderSubmitter orderSubmitter;      // IMMEDIATE 재주문 선점 → 접수 → 기록 (배치와 같은 경로)
    private final StrategyLedgerBudget ledgerBudget;  // BUY 재주문 전략 원장 상한

    @Override
    public ReorderResult reorder(ReorderCommand command) {
        return reorder(command, DstInfo.calculate(), Instant.now());
    }

    // 테스트 주입용 — DstInfo + 판정 시각 직접 지정
    ReorderResult reorder(ReorderCommand command, DstInfo dst, Instant now) {
        SelectionChain.Selection sel = SelectionChain.resolveAndValidate(
                accountPort, strategyPort, command.accountId(), command.strategyId(), command.userId());
        Account account = sel.account();
        Strategy strategy = sel.strategy();
        StrategyCycle currentCycle = strategyCyclePort.requireLatestByStrategyId(strategy.id());
        Order sourceOrder = orderPort.findById(command.orderId())
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        SelectionChain.validate(command.userId(), account, strategy, sourceOrder);
        // 재주문은 주식 정수 수량 전용 — 코인 주문(소수 수량)은 관리자 재주문 대상이 아니다
        if (account.broker().isCryptoExchange()) {
            throw new IllegalArgumentException("코인 주문은 재주문할 수 없습니다.");
        }
        if (!sourceOrder.strategyCycleId().equals(currentCycle.id())) {
            throw new IllegalArgumentException("현재 전략 사이클 주문만 재주문할 수 있습니다");
        }
        // 미체결(PLANNED·PLACED) 원본만 — 체결·취소·실패한 주문 위에 새 주문을 얹으면 같은 주문이 중복으로 나간다(체결분 보정은 수동 체결 보정)
        if (sourceOrder.status() != OrderStatus.PLANNED && sourceOrder.status() != OrderStatus.PLACED) {
            throw new IllegalArgumentException("미체결(PLANNED·PLACED) 주문만 재주문할 수 있습니다");
        }

        BigDecimal price = requirePrice(command);
        int quantity = requireQuantity(command);
        OrderDirection direction = command.direction() != null ? command.direction() : sourceOrder.direction();
        LocalDate tradeDate = command.tradeDate() != null ? command.tradeDate() : sourceOrder.tradeDate();

        // 1. 주문시점 가용성 서버 측 재검증 (UI disable 우회 방지) — 증권사 취소보다 먼저: 검증 실패 시 원본이 취소된 채 남지 않도록
        // 판정 시각 기준 거래일(04:30 KST 경계) — 오늘 KST 일자를 넘기면 포트가 직전 세션을 검사한다
        LocalDate currentTradeDate = DstInfo.nextTradeDate(now.atZone(TimeZones.KST));
        if (!marketCalendarPort.isMarketOpen(currentTradeDate)) {
            throw new IllegalArgumentException("휴장일에는 재주문할 수 없습니다");
        }
        DstInfo.ReorderTimingAvailability avail = dst.reorderTimingAvailabilityAt(now);
        boolean timingOk = switch (command.timing()) {
            case AT_OPEN -> avail.atOpen();
            case AT_CLOSE -> avail.atClose();
            case IMMEDIATE -> avail.immediate();
        };
        if (!timingOk) {
            throw new IllegalArgumentException("현재 시장 단계에서 " + command.timing() + " 접수가 불가합니다");
        }
        // 시점 가용성은 현재 세션 기준이라 재주문도 현재 거래일만 — 다른 날짜면 배치가 접수하지 않거나(과거)
        // 체결 리포트가 짝을 못 찾아 PLACED로 남는다(IMMEDIATE)
        if (!tradeDate.equals(currentTradeDate)) {
            throw new IllegalArgumentException("현재 거래일(" + currentTradeDate + ") 주문으로만 재주문할 수 있습니다");
        }

        // 1-1. BUY는 전략 원장 상한 검증 — 원본 취소 전에 (거절 시 취소·증권사 호출 없음, 축소 없는 단건)
        // remaining이 같은 거래일 PLANNED·PLACED BUY 원본을 이미 차감했으므로 취소로 풀리는 환급분만 더한다
        if (direction == OrderDirection.BUY) {
            BigDecimal limit = ledgerBudget.remaining(currentCycle, tradeDate)
                    .add(ReorderBuyBudgetService.refundOf(sourceOrder, tradeDate));
            if (price.multiply(BigDecimal.valueOf(quantity)).compareTo(limit) > 0) {
                throw new ManualTradingException("전략 원장 예수금이 부족합니다.");
            }
        }

        // 2. 원본 상태별 취소 처리
        cancelSource(sourceOrder, account);

        // 3. 재주문 생성 — timing에 따라 PLANNED 저장 또는 즉시 증권사 접수
        Order newOrder = Order.reorder(sourceOrder, tradeDate, direction, quantity, price, command.timing());
        PlacementResult placement = placeOrSave(newOrder, account, command.timing());

        // trading 쪽 독립 감사 기록 — admin 프록시(AdminReorderService.auditLogPort)와 별개로,
        // 내부 엔드포인트를 직접 호출한 경우에도 trading 로그에는 반드시 남긴다
        log.info("재주문 처리: userId={}, accountId={}, strategyId={}, sourceOrderId={}, originalStatus={}, resultingStatus={}, externalOrderId={}",
                command.userId(), command.accountId(), command.strategyId(), sourceOrder.id(),
                sourceOrder.status(), placement.status(), placement.externalOrderId());

        return new ReorderResult(command.userId(), command.accountId(), command.strategyId(),
                sourceOrder.id(), sourceOrder.status(), placement.status(), placement.externalOrderId(),
                sourceOrder.price(), sourceOrder.quantity().intValueExact(), direction);
    }

    // 원본 취소 — PLANNED: DB만 CANCELLED, PLACED: 증권사 취소 + DB CANCELLED (그 밖의 상태는 앞에서 거부됨)
    private void cancelSource(Order order, Account account) {
        if (order.status() == OrderStatus.PLACED) {
            brokerOrderCorrectionPort
                    .cancel(new CancelInstruction(order.ticker(), order.externalOrderId()), account.toBrokerRef());
            stateWriter.markCancelled(order.id());
            return;
        }
        // PLANNED일 때만 전이 — 읽은 뒤 배치가 접수했으면 새 주문을 얹지 않고 중단(원본이 그대로 남아 중복 없음)
        try {
            stateWriter.cancelPlanned(order.id());
        } catch (OrderStateConflictException e) {
            throw new IllegalArgumentException("원본 주문 상태가 바뀌었습니다. 다시 조회한 뒤 시도해 주세요");
        }
    }

    // AT_OPEN/AT_CLOSE: PLANNED 저장 / IMMEDIATE: PLANNED로 먼저 저장(id 확보) 후 배치와 같은 선점→접수 경로(OrderSubmitter)
    private PlacementResult placeOrSave(Order newOrder, Account account, com.kista.shared.kernel.OrderTiming timing) {
        if (timing != com.kista.shared.kernel.OrderTiming.IMMEDIATE) {
            orderPort.saveAll(List.of(newOrder));
            return new PlacementResult(OrderStatus.PLANNED, null);
        }
        // 저장 실패면 증권사에 보내지 않는다 — 접수 후 저장 실패로 DB↔증권사가 갈라지는 경로 제거
        Order saved = orderPort.save(newOrder);
        OrderSubmitter.Submission submission = orderSubmitter.submit(saved, account.toBrokerRef(), account.nickname());
        return switch (submission.outcome()) {
            case PLACED -> new PlacementResult(OrderStatus.PLACED, submission.externalOrderId());
            case REJECTED, INSUFFICIENT_FUNDS -> new PlacementResult(OrderStatus.FAILED, null); // 잔고 부족은 FAILED 응답으로 사용자에게 직접 전달 — 별도 알림 없음
            case UNKNOWN -> new PlacementResult(OrderStatus.SUBMITTING, null); // 결과 불명 — 재시도 금지, 런북 정정
            case SKIPPED -> resolveSkipped(saved);
        };
    }

    // 선점 못 함 — 배치가 먼저 선점했으면 그 상태 그대로, 선점 오류로 PLANNED(IMMEDIATE)가 남았으면 마감 배치가 나중에 접수하지 않게 취소
    private PlacementResult resolveSkipped(Order saved) {
        Order latest = orderPort.findById(saved.id()).orElse(null);
        if (latest == null) return new PlacementResult(OrderStatus.CANCELLED, null);
        if (latest.status() != OrderStatus.PLANNED) return new PlacementResult(latest.status(), latest.externalOrderId());
        try {
            stateWriter.cancelPlanned(saved.id());
            return new PlacementResult(OrderStatus.CANCELLED, null);
        } catch (OrderStateConflictException e) {
            // 그 사이 배치가 선점 — 최신 상태로 응답
            return orderPort.findById(saved.id())
                    .map(o -> new PlacementResult(o.status(), o.externalOrderId()))
                    .orElse(new PlacementResult(OrderStatus.CANCELLED, null));
        } catch (Exception e) {
            // 정리 취소도 실패 — 원본은 이미 취소됐으므로 예외로 끊지 않고 사실대로 PLANNED 응답(마감 배치가 접수할 수 있음)
            log.error("재주문 IMMEDIATE 선점·정리 모두 실패 — PLANNED 잔류, 마감 배치 접수 가능 (orderId={}): {}", saved.id(), e.getMessage());
            return new PlacementResult(OrderStatus.PLANNED, null);
        }
    }

    private record PlacementResult(OrderStatus status, String externalOrderId) {}

    private static BigDecimal requirePrice(ReorderCommand command) {
        if (command.price() == null || command.price().signum() <= 0) {
            throw new IllegalArgumentException("price는 양수여야 합니다");
        }
        return command.price();
    }

    private static int requireQuantity(ReorderCommand command) {
        if (command.quantity() == null || command.quantity() <= 0) {
            throw new IllegalArgumentException("quantity는 양수여야 합니다");
        }
        return command.quantity();
    }
}

