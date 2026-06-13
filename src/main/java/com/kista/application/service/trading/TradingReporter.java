package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.Account.Broker;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.TradeEvent;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.ReverseModePosition;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.strategy.TradingSnapshot;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.CyclePositionPort;
import com.kista.domain.port.out.KisExecutionPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.RealtimeNotificationPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.UserNotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;

// 체결 조회 + 사이클 포지션 저장 + 텔레그램·SSE 알림 + 연속 정책 처리
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReporter {

    private final KisExecutionPort kisExecutionPort;
    private final OrderPort orderPort;
    private final UserNotificationPort userNotificationPort;
    private final RealtimeNotificationPort realtimeNotificationPort;
    private final CyclePositionPort cyclePositionPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CycleRotationService cycleRotationService;

    void recordAndNotify(LocalDate today, Strategy strategy, StrategyCycle currentCycle,
                         Account account, User user,
                         AccountBalance balance, TradingSnapshot snapshot, BigDecimal closingPrice,
                         List<Order> mainOrders, PrivacyTradeBase privacyBase) {
        // Toss 계좌는 체결 조회 API 없음 (MVP) — 주문 PLACED 상태 유지
        // today는 KST → KisTradingApi.getExecutions 내부에서 toUtc 변환됨
        List<Execution> executions = account.broker() == Broker.TOSS
                ? Collections.emptyList()
                : kisExecutionPort.getExecutions(today, today, strategy.ticker(), account);
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로)
        AccountBalance postBalance = balance.applyExecutions(executions);
        saveCyclePosition(today, postBalance, strategy, currentCycle, account, user, closingPrice, privacyBase);

        // 접수된 주문별 체결 현황 기록 (FILLED / PARTIALLY_FILLED)
        markFilledOrders(mainOrders, executions);

        if (snapshot != null) { // PRIVACY 전략은 스냅샷 없음 — 텔레그램 리포트 생략
            // 매매 후 상태로 스냅샷 재계산 (종가 기준 편차율·목표가 포함)
            TradingSnapshot postSnapshot = closingPrice != null
                    ? new InfinitePosition(postBalance, strategy.ticker(), closingPrice, strategy.divisionCount()).toSnapshot()
                    : snapshot;
            TradingReport report = buildReport(today, postSnapshot, mainOrders, executions);
            userNotificationPort.notifyTradingReport(user, account, report);
            log.info("[{}] 텔레그램 리포트 발송 완료", account.nickname());
        }
        // 체결 건별 SSE 실시간 알림
        for (Execution e : executions) {
            TradeEvent event = e.direction() == SELL
                    ? TradeEvent.sell(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname())
                    : TradeEvent.buy(e.ticker().name(), e.quantity(), e.price().doubleValue(), e.amountUsd().doubleValue(), account.nickname());
            realtimeNotificationPort.notifyTrade(user.id(), event);
        }
        log.info("[{}] SSE 매매 알림 {}건 발송 완료", account.nickname(), executions.size());
    }

    // execute() 종료 시 1건 적재, holdings==0이면 사이클 rotation 정책 처리
    // 리버스모드 소진 감지 및 복귀 조건도 여기서 처리
    private void saveCyclePosition(LocalDate today, AccountBalance balance, Strategy strategy, StrategyCycle currentCycle,
                                   Account account, User user, BigDecimal price, PrivacyTradeBase privacyBase) {
        CyclePosition position = CyclePosition.tradeSnapshot(currentCycle.id(), balance, price);
        cyclePositionPort.save(position);
        log.info("[strategyId={}] 사이클 포지션 저장 완료", strategy.id());

        // INFINITE 전략 전용 — 리버스모드 소진 감지 + 복귀 조건 처리
        if (strategy.type() == Strategy.Type.INFINITE) {
            handleReverseModeTransition(strategy, currentCycle, balance, price);
        }

        // holdings==0이면 사이클 종료 알림 후 CycleSeedType 무관하게 항상 rotate 호출 — NONE 처리는 rotate 내부에서
        if (position.holdings() == 0) {
            // 사이클 종료 기록 — 종료금액=청산 후 통합주문가능금액, 종료일자=KST 매매일
            strategyCyclePort.markEnded(currentCycle.id(), balance.usdDeposit(), today);
            log.info("[strategyId={}] 사이클 종료 — 연속 정책 실행: {}", strategy.id(), strategy.cycleSeedType());
            userNotificationPort.notifyCycleCompleted(user, account, strategy);
            cycleRotationService.rotate(strategy, currentCycle, account, user, price, privacyBase);
        }
    }

    // 리버스모드 전환 처리:
    // 1) 일반모드에서 소진 발동(isFinalRound) → markReverseMode
    // 2) 리버스모드에서 복귀 조건 충족(closingPrice > avgPrice*(1-targetProfitRate)) → markNormalMode
    private void handleReverseModeTransition(Strategy strategy, StrategyCycle currentCycle,
                                              AccountBalance balance, BigDecimal closingPrice) {
        if (currentCycle.isReverseMode()) {
            // 리버스모드 종료 조건 — 종가 > 평단 × (1 - targetProfitRate)이면 일반모드 복귀
            if (closingPrice != null && balance.avgPrice() != null && balance.holdings() > 0) {
                ReverseModePosition rp = new ReverseModePosition(
                        balance.holdings(), balance.avgPrice(), balance.usdDeposit(),
                        strategy.ticker(), strategy.divisionCount(), null, false);
                if (rp.shouldExitReverseMode(closingPrice, strategy.ticker().getTargetProfitRate())) {
                    strategyCyclePort.markNormalMode(currentCycle.id());
                    log.info("[strategyId={}] 리버스모드 종료 → 일반모드 복귀 (closingPrice={}, avgPrice={})",
                            strategy.id(), closingPrice, balance.avgPrice());
                }
            }
        } else {
            // 일반모드에서 소진 감지(isFinalRound) — 보유수량 > 0인 경우만 체크
            if (balance.holdings() > 0 && closingPrice != null) {
                // closingPrice를 prevClosePrice로 사용 (holdings>0이면 averagePrice로 자동 대체됨)
                InfinitePosition ip = new InfinitePosition(balance, strategy.ticker(), closingPrice, strategy.divisionCount());
                if (ip.isFinalRound()) {
                    strategyCyclePort.markReverseMode(currentCycle.id());
                    log.info("[strategyId={}] 소진 발동 → 리버스모드 진입 (unitAmount={}, usdDeposit={})",
                            strategy.id(), ip.unitAmount(), balance.usdDeposit());
                }
            }
        }
    }

    // 접수 주문과 실체결 내역을 externalOrderId 기준으로 매칭하여 FILLED / PARTIALLY_FILLED 기록
    private void markFilledOrders(List<Order> mainOrders, List<Execution> executions) {
        if (executions.isEmpty()) return;

        // externalOrderId → 체결 목록 그룹핑 (1:N 체결 허용)
        Map<String, List<Execution>> byOrderId = executions.stream()
                .filter(e -> e.externalOrderId() != null && !e.externalOrderId().isBlank())
                .collect(Collectors.groupingBy(Execution::externalOrderId));

        for (Order order : mainOrders) {
            if (order.externalOrderId() == null) continue;
            List<Execution> matched = byOrderId.get(order.externalOrderId());
            if (matched == null || matched.isEmpty()) continue; // 미체결 유지

            int filledQty = matched.stream().mapToInt(Execution::quantity).sum();
            // 가중평균 체결가: Σ(체결금액) ÷ Σ(체결수량)
            BigDecimal totalAmt = matched.stream().map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgFilledPrice = filledQty > 0
                    ? totalAmt.divide(BigDecimal.valueOf(filledQty), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            int orderedQty = order.quantity() != null ? order.quantity() : 0;
            Order.OrderStatus newStatus = filledQty >= orderedQty
                    ? Order.OrderStatus.FILLED
                    : Order.OrderStatus.PARTIALLY_FILLED;

            orderPort.markFilled(order.id(), filledQty, avgFilledPrice, newStatus);
            log.info("[orderId={}] {} → {}, 체결수량={}/{}", order.id(), order.status(), newStatus, filledQty, orderedQty);
        }
    }

    private TradingReport buildReport(LocalDate today, TradingSnapshot snapshot,
                                      List<Order> mainOrders, List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, snapshot, mainOrders, totalBought, totalSold);
    }
}
