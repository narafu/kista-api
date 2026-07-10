package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.TradeEvent;
import com.kista.domain.model.privacy.PrivacyTradeBase;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.user.NotificationType;
import com.kista.domain.model.user.User;
import com.kista.domain.model.user.UserSettings;
import com.kista.domain.port.out.*;
import com.kista.domain.port.out.broker.ExecutionPort;
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

// 체결 조회 + 알림 발송 (포지션 저장은 CyclePositionPersistor에 위임)
@Component
@RequiredArgsConstructor
@Slf4j
class TradingReporter {

    private final BrokerAdapterRegistry registry;
    private final OrderPort orderPort;                              // 주문 체결 상태 갱신
    private final UserNotificationPort userNotificationPort;        // 리포트 알림 발송
    private final RealtimeNotificationPort realtimeNotificationPort; // SSE 실시간 알림
    private final UserSettingsPort userSettingsPort;                // TRADING_ALERT 알림 활성 여부 조회
    private final CyclePositionPersistor cyclePositionPersistor;   // 포지션 스냅샷 저장 위임

    void recordAndNotify(LocalDate today, BatchContext ctx, AccountBalance balance,
                         BigDecimal closingPrice, List<Order> mainOrders, PrivacyTradeBase privacyBase) {
        Strategy strategy = ctx.strategy();
        Account account = ctx.account();
        User user = ctx.user();
        // today는 KST — KIS는 어댑터에서 toUtc 변환, Toss는 KST 날짜 그대로 전달
        List<Execution> executions = registry.require(account, ExecutionPort.class).getExecutions(today, today, strategy.ticker(), account);
        log.info("[{}] 체결 내역 {}건 조회", account.nickname(), executions.size());

        // 체결 결과로 매매 후 잔고 계산 (체결 없으면 pre-trade 그대로)
        AccountBalance postBalance = balance.applyExecutions(executions);
        cyclePositionPersistor.saveCyclePosition(today, postBalance, ctx, closingPrice, privacyBase);

        // 접수된 주문별 체결 현황 기록 (FILLED / PARTIALLY_FILLED)
        markFilledOrders(mainOrders, executions);

        // TRADING_ALERT 알림 활성 여부 확인 후 발송 (기본값 true)
        TradingReport report = buildReport(today, strategy.type(), strategy.ticker(), executions);
        UserSettings settings = userSettingsPort.findOrDefault(user.id());
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

    // 접수 주문과 실체결 내역을 externalOrderId 기준으로 매칭하여 FILLED / PARTIALLY_FILLED 기록
    private void markFilledOrders(List<Order> mainOrders, List<Execution> executions) {
        // externalOrderId → 체결 목록 그룹핑 (1:N 체결 허용)
        Map<String, List<Execution>> byOrderId = executions.stream()
                .filter(e -> e.externalOrderId() != null && !e.externalOrderId().isBlank())
                .collect(Collectors.groupingBy(Execution::externalOrderId));

        for (Order order : mainOrders) {
            if (order.externalOrderId() == null) continue;
            List<Execution> matched = byOrderId.get(order.externalOrderId());
            if (matched == null || matched.isEmpty()) {
                // 체결 내역 없음 → 미체결(LOC/MOC 당일 자동 취소) → CANCELLED
                orderPort.markCancelled(order.id());
                log.info("[orderId={}] 미체결 → CANCELLED", order.id());
                continue;
            }

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
