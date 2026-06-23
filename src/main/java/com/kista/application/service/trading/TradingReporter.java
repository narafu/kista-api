package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.TradeEvent;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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

    private final BrokerExecutionRouter brokerExecutionRouter;
    private final OrderPort orderPort;
    private final UserNotificationPort userNotificationPort;
    private final RealtimeNotificationPort realtimeNotificationPort;
    private final CyclePositionPort cyclePositionPort;
    private final StrategyCyclePort strategyCyclePort;
    private final CycleRotationService cycleRotationService;
    private final LoadUserSettingsPort loadUserSettingsPort; // TRADING_ALERT 알림 활성 여부 조회

    void recordAndNotify(LocalDate today, Strategy strategy, StrategyCycle currentCycle,
                         Account account, User user,
                         AccountBalance balance, BigDecimal closingPrice,
                         List<Order> mainOrders, PrivacyTradeBase privacyBase) {
        // today는 KST — KIS는 어댑터에서 toUtc 변환, Toss는 KST 날짜 그대로 전달
        List<Execution> executions = brokerExecutionRouter.getExecutions(today, today, strategy.ticker(), account);
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로)
        AccountBalance postBalance = balance.applyExecutions(executions);
        saveCyclePosition(today, postBalance, strategy, currentCycle, account, user, closingPrice, privacyBase);

        // 접수된 주문별 체결 현황 기록 (FILLED / PARTIALLY_FILLED)
        markFilledOrders(mainOrders, executions);

        // TRADING_ALERT 알림 활성 여부 확인 후 발송 (기본값 true)
        TradingReport report = buildReport(today, strategy.type(), strategy.ticker(), executions);
        UserSettings settings = loadUserSettingsPort.loadByUserId(user.id())
                .orElse(UserSettings.defaultFor(user.id()));
        if (settings.isNotificationEnabled(NotificationType.TRADING_ALERT)) {
            userNotificationPort.notifyTradingReport(user, account, report);
            log.info("[{}] 리포트 발송 완료", account.nickname());
        } else {
            log.info("[{}] TRADING_ALERT 비활성 — 리포트 발송 생략", account.nickname());
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
    private void saveCyclePosition(LocalDate today, AccountBalance balance, Strategy strategy, StrategyCycle currentCycle,
                                   Account account, User user, BigDecimal price, PrivacyTradeBase privacyBase) {
        // INFINITE 전략: cycle_position 최신 행을 기반으로 상태 머신으로 새 모드 결정
        boolean newReverseMode = false;
        if (strategy.isInfinite()) {
            newReverseMode = computeNewReverseMode(currentCycle.id(), strategy, balance, price);
        }

        // 저장 전 이전 포지션 확인 — 0회차 매수 실패(holdings=0)와 진짜 청산(이전 holdings>0→현재 0) 구분
        List<CyclePosition> prevPositions = cyclePositionPort.findLatestByCycleId(currentCycle.id(), 1);
        boolean prevHadHoldings = !prevPositions.isEmpty() && prevPositions.get(0).holdings() > 0;

        CyclePosition position = CyclePosition.tradeSnapshot(currentCycle.id(), balance, price, newReverseMode);
        cyclePositionPort.save(position);
        log.info("[strategyId={}] 사이클 포지션 저장 완료 (isReverseMode={})", strategy.id(), newReverseMode);

        // holdings==0이면서 이전에 보유 이력이 있을 때만 사이클 종료 처리
        // (0회차 매수 실패 케이스: startSnapshot→tradeSnapshot 모두 holdings=0이므로 여기서 걸림)
        if (position.holdings() == 0 && prevHadHoldings) {
            // 사이클 종료 기록 — 종료금액=청산 후 통합주문가능금액, 종료일자=KST 매매일
            strategyCyclePort.markEnded(currentCycle.id(), balance.usdDeposit(), today);
            log.info("[strategyId={}] 사이클 종료 — 연속 정책 실행: {}", strategy.id(), strategy.cycleSeedType());
            userNotificationPort.notifyCycleCompleted(user, account, strategy);
            cycleRotationService.rotate(strategy, currentCycle, account, user, price, privacyBase);
        }
    }

    // 체결 후 포지션 기반 리버스모드 상태 머신
    // 직전 행 is_reverse_mode → 진입/유지/종료 판정
    private boolean computeNewReverseMode(java.util.UUID cycleId, Strategy strategy,
                                           AccountBalance balance, BigDecimal closingPrice) {
        List<CyclePosition> recent = cyclePositionPort.findLatestByCycleId(cycleId, 1);
        boolean prevReverseMode = !recent.isEmpty() && recent.get(0).isReverseMode();

        if (prevReverseMode) {
            // 리버스모드 종료 조건: avgPrice × (1 - targetProfitRate) ≤ closingPrice
            if (closingPrice != null && balance.avgPrice() != null && balance.holdings() > 0) {
                ReverseModePosition rp = new ReverseModePosition(
                        balance.holdings(), balance.avgPrice(), balance.usdDeposit(),
                        strategy.ticker(), strategy.divisionCount(), null, false);
                if (rp.shouldExitReverseMode(closingPrice, strategy.ticker().getTargetProfitRate())) {
                    log.info("[strategyId={}] 리버스모드 종료 → 일반모드 복귀 (closingPrice={}, avgPrice={})",
                            strategy.id(), closingPrice, balance.avgPrice());
                    return false;
                }
            }
            return true;
        } else {
            // 일반모드 → 소진 감지: usdDeposit < unitAmount (isFinalRound)
            if (balance.holdings() > 0 && closingPrice != null) {
                // closingPrice를 prevClosePrice로 사용 (holdings>0이면 averagePrice로 자동 대체됨)
                InfinitePosition ip = new InfinitePosition(balance, strategy.ticker(), closingPrice, strategy.divisionCount());
                if (ip.isFinalRound()) {
                    log.info("[strategyId={}] 소진 발동 → 리버스모드 진입 (unitAmount={}, usdDeposit={})",
                            strategy.id(), ip.unitAmount(), balance.usdDeposit());
                    return true;
                }
            }
            return false;
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

            int filledQuantity = matched.stream().mapToInt(Execution::quantity).sum();
            // 가중평균 체결가: Σ(체결금액) ÷ Σ(체결수량)
            BigDecimal totalAmt = matched.stream().map(Execution::amountUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgFilledPrice = filledQuantity > 0
                    ? totalAmt.divide(BigDecimal.valueOf(filledQuantity), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            int orderedQuantity = order.quantity() != null ? order.quantity() : 0;
            Order.OrderStatus newStatus = filledQuantity >= orderedQuantity
                    ? Order.OrderStatus.FILLED
                    : Order.OrderStatus.PARTIALLY_FILLED;

            orderPort.markFilled(order.id(), filledQuantity, avgFilledPrice, newStatus);
            log.info("[orderId={}] {} → {}, 체결수량={}/{}", order.id(), order.status(), newStatus, filledQuantity, orderedQuantity);
        }
    }

    private TradingReport buildReport(LocalDate today, Strategy.Type strategyType, Strategy.Ticker ticker, List<Execution> executions) {
        BigDecimal totalBought = executions.stream()
                .filter(e -> e.direction() == BUY)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSold = executions.stream()
                .filter(e -> e.direction() == SELL)
                .map(Execution::amountUsd)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TradingReport(today, strategyType, ticker, totalBought, totalSold);
    }
}
