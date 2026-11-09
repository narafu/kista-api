package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.domain.model.DstInfo;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;

// 마감 배치 리포트 대상 판정 SSOT — 정지 전략은 증권사 접수 주문이 있을 때만 리포트(CQ-25), 활성은 전략 주문이 있으면
final class CloseReportScope {

    private CloseReportScope() {}

    // 마감 리포트 창(오늘 postClose 이후 ~ 리포트 마커 전)에 이 전략이 리포트 대기 중인지 — 창 거절 로직(CapitalInjectionService·ManualTradeCorrectionService)의 공통 판정
    // postClose 이전이면 리포트가 최신 상태를 재조회하므로 안전. 리포트 마커가 이미 찍혔거나 리포트 대상 주문이 없으면 창 밖
    static boolean inReportWindow(Strategy strategy, Clock clock, TradingBatchRunPort batchRunPort, OrderPort orderPort) {
        if (DstInfo.calculate(ZonedDateTime.now(clock)).waitUntilPostClose(Instant.now(clock)).isPositive()) {
            return false;
        }
        LocalDate today = LocalDate.now(clock);
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
