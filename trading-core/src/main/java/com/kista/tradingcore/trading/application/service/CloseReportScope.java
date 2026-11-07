package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;

import java.util.List;

// 마감 배치 리포트 대상 판정 SSOT — 정지 전략은 증권사 접수 주문이 있을 때만 리포트(CQ-25), 활성은 전략 주문이 있으면
final class CloseReportScope {

    private CloseReportScope() {}

    // 수동 보정 행(UNKNOWN_LEG)은 전략 주문이 아니므로 제외
    static boolean awaitsCloseReport(Strategy strategy, List<Order> todayOrders) {
        boolean paused = !strategy.isActive();
        return todayOrders.stream()
                .filter(o -> !Order.UNKNOWN_LEG.equals(o.orderLeg()))
                .anyMatch(o -> !paused || o.externalOrderId() != null);
    }
}
