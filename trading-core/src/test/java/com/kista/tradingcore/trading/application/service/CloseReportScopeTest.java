package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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

    // 비DST 1월: postClose = 06:10 KST. 2026-01-05 거래일
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate TODAY = LocalDate.of(2026, 1, 5);
    private static final Clock BEFORE_POST_CLOSE = Clock.fixed(Instant.parse("2026-01-04T20:00:00Z"), KST); // 05:00 KST
    private static final Clock AFTER_POST_CLOSE = Clock.fixed(Instant.parse("2026-01-04T22:00:00Z"), KST);  // 07:00 KST

    @Test
    void inReportWindow_beforePostClose_false() {
        TradingBatchRunPort batchRunPort = mock(TradingBatchRunPort.class);
        OrderPort orderPort = mock(OrderPort.class);

        assertThat(CloseReportScope.inReportWindow(strategy(StrategyStatus.ACTIVE), BEFORE_POST_CLOSE, batchRunPort, orderPort)).isFalse();
        verifyNoInteractions(batchRunPort, orderPort);
    }

    @Test
    void inReportWindow_reportMarked_false() {
        Strategy s = strategy(StrategyStatus.ACTIVE);
        TradingBatchRunPort batchRunPort = mock(TradingBatchRunPort.class);
        OrderPort orderPort = mock(OrderPort.class);
        when(batchRunPort.isReported(TODAY, s.id())).thenReturn(true);

        assertThat(CloseReportScope.inReportWindow(s, AFTER_POST_CLOSE, batchRunPort, orderPort)).isFalse();
        verifyNoInteractions(orderPort);
    }

    @Test
    void inReportWindow_delegatesToAwaitsCloseReport() {
        Strategy s = strategy(StrategyStatus.ACTIVE);
        TradingBatchRunPort batchRunPort = mock(TradingBatchRunPort.class);
        OrderPort orderPort = mock(OrderPort.class);
        when(batchRunPort.isReported(TODAY, s.id())).thenReturn(false);
        Order locBuy = order("LOC_BUY", null);
        when(orderPort.findByStrategyId(s.id(), TODAY, TODAY)).thenReturn(List.of(locBuy));
        assertThat(CloseReportScope.inReportWindow(s, AFTER_POST_CLOSE, batchRunPort, orderPort)).isTrue();

        when(orderPort.findByStrategyId(s.id(), TODAY, TODAY)).thenReturn(List.of());
        assertThat(CloseReportScope.inReportWindow(s, AFTER_POST_CLOSE, batchRunPort, orderPort)).isFalse();
    }
}
