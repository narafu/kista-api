package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloseReportScopeTest {

    private static Strategy strategy(StrategyStatus status) {
        return new Strategy(UUID.randomUUID(), UUID.randomUUID(), StrategyType.PRIVACY, status, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
    }

    private static Order order(String leg, String externalOrderId) {
        Order o = mock(Order.class);
        when(o.isStrategyOrder()).thenReturn(!Order.UNKNOWN_LEG.equals(leg)); // 서비스는 orderLeg 대신 술어로 판정
        when(o.externalOrderId()).thenReturn(externalOrderId);
        return o;
    }

    @Test
    void active_anyStrategyOrder() {
        assertThat(CloseReportScope.awaitsCloseReport(strategy(StrategyStatus.ACTIVE), List.of(order("LOC_BUY", null)))).isTrue();
        assertThat(CloseReportScope.awaitsCloseReport(strategy(StrategyStatus.ACTIVE), List.of(order(Order.UNKNOWN_LEG, "E1")))).isFalse();
        assertThat(CloseReportScope.awaitsCloseReport(strategy(StrategyStatus.ACTIVE), List.of())).isFalse();
    }

    @Test
    void paused_onlyPlacedStrategyOrder() {
        assertThat(CloseReportScope.awaitsCloseReport(strategy(StrategyStatus.PAUSED), List.of(order("LOC_BUY", null)))).isFalse();
        assertThat(CloseReportScope.awaitsCloseReport(strategy(StrategyStatus.PAUSED), List.of(order(Order.UNKNOWN_LEG, "E1")))).isFalse();
        assertThat(CloseReportScope.awaitsCloseReport(strategy(StrategyStatus.PAUSED), List.of(order("LOC_BUY", "E1")))).isTrue();
    }
}
