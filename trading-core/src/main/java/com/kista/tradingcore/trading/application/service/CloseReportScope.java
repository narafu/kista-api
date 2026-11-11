package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

// 마감 배치 리포트 대상 판정 SSOT — 정지 전략은 증권사 접수 주문이 있을 때만 리포트(CQ-25), 활성은 전략 주문이 있으면
final class CloseReportScope {

    private CloseReportScope() {}

    // 창 거절 로직(CapitalLedgerService·ManualTradeCorrectionService·StrategyUpdateWriter)의 공통 판정 — 실제 postClose 이전에는 리포트가 최신 상태를 재조회한다. 휴장은 보수적으로 종일 보호하고 미적재 예외는 전파한다
    static boolean inReportWindow(Strategy strategy, Clock clock, TradingBatchRunPort batchRunPort, OrderPort orderPort,
                                  MarketCalendarPort calendar) {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(TimeZones.KST);
        var session = calendar.findSession(now.toLocalDate());
        if (session.isPresent() && now.toInstant().isBefore(session.get().closeAt().plus(Duration.ofMinutes(10)))) {
            return false;
        }
        return awaitsReportToday(strategy, clock, batchRunPort, orderPort);
    }

    private static boolean awaitsReportToday(Strategy strategy, Clock clock, TradingBatchRunPort batchRunPort, OrderPort orderPort) {
        LocalDate today = Instant.now(clock).atZone(TimeZones.KST).toLocalDate();
        if (batchRunPort.isReported(today, strategy.id())) {
            return false;
        }
        return awaitsCloseReport(strategy, orderPort.findByStrategyId(strategy.id(), today, today));
    }

    // 수동 보정 행(UNKNOWN_LEG)은 전략 주문이 아니므로 제외
    static boolean awaitsCloseReport(Strategy strategy, List<Order> todayOrders) {
        boolean paused = !strategy.isActive();
        return todayOrders.stream()
                .filter(Order::isStrategyOrder)
                .anyMatch(o -> !paused || o.externalOrderId() != null);
    }
}
