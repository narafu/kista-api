package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.TradingBatchRunPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
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

    // 2026-01-05 KST 거래일 = 미국 정규장 23:30~06:00 KST
    private static MarketCalendarPort regularCalendar() {
        MarketCalendarPort calendar = mock(MarketCalendarPort.class);
        when(calendar.findSession(TODAY)).thenReturn(Optional.of(new MarketSessionTimes(
                Instant.parse("2026-01-04T14:30:00Z"), Instant.parse("2026-01-04T21:00:00Z"))));
        return calendar;
    }

    @Test
    void inReportWindow_beforePostClose_false() {
        TradingBatchRunPort batchRunPort = mock(TradingBatchRunPort.class);
        OrderPort orderPort = mock(OrderPort.class);
        MarketCalendarPort calendar = mock(MarketCalendarPort.class);
        when(calendar.findSession(TODAY)).thenReturn(Optional.of(new MarketSessionTimes(
                Instant.parse("2026-01-04T14:30:00Z"), Instant.parse("2026-01-04T21:00:00Z"))));

        assertThat(CloseReportScope.inReportWindow(strategy(StrategyStatus.ACTIVE), BEFORE_POST_CLOSE, batchRunPort, orderPort, calendar)).isFalse();
        verifyNoInteractions(batchRunPort, orderPort);
    }

    @Test
    void inReportWindow_reportMarked_false() {
        Strategy s = strategy(StrategyStatus.ACTIVE);
        TradingBatchRunPort batchRunPort = mock(TradingBatchRunPort.class);
        OrderPort orderPort = mock(OrderPort.class);
        when(batchRunPort.isReported(TODAY, s.id())).thenReturn(true);

        assertThat(CloseReportScope.inReportWindow(s, AFTER_POST_CLOSE, batchRunPort, orderPort, regularCalendar())).isFalse();
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
        assertThat(CloseReportScope.inReportWindow(s, AFTER_POST_CLOSE, batchRunPort, orderPort, regularCalendar())).isTrue();

        when(orderPort.findByStrategyId(s.id(), TODAY, TODAY)).thenReturn(List.of());
        assertThat(CloseReportScope.inReportWindow(s, AFTER_POST_CLOSE, batchRunPort, orderPort, regularCalendar())).isFalse();
    }

    @Test
    void earlyCloseWindowStartsAtActualPostCloseAndEndsAtMarker() {
        Strategy s = strategy(StrategyStatus.ACTIVE);
        TradingBatchRunPort batch = mock(TradingBatchRunPort.class);
        OrderPort orders = mock(OrderPort.class);
        MarketCalendarPort calendar = mock(MarketCalendarPort.class);
        Instant close = Instant.parse("2026-01-04T18:00:00Z"); // 03:00 KST
        when(calendar.findSession(TODAY)).thenReturn(Optional.of(new MarketSessionTimes(close.minusSeconds(12600), close)));
        Order locBuy = order("LOC_BUY", null);
        when(orders.findByStrategyId(s.id(), TODAY, TODAY)).thenReturn(List.of(locBuy));
        Clock before = Clock.fixed(close.plusSeconds(599), KST);
        Clock at = Clock.fixed(close.plusSeconds(600), KST);
        assertThat(CloseReportScope.inReportWindow(s, before, batch, orders, calendar)).isFalse();
        assertThat(CloseReportScope.inReportWindow(s, at, batch, orders, calendar)).isTrue();
        when(batch.isReported(TODAY, s.id())).thenReturn(true);
        assertThat(CloseReportScope.inReportWindow(s, at, batch, orders, calendar)).isFalse();
    }

    @Test
    void holidayAndMissingDataNeverAllowUnprotectedLedgerMutation() {
        Strategy s = strategy(StrategyStatus.ACTIVE);
        TradingBatchRunPort batch = mock(TradingBatchRunPort.class);
        OrderPort orders = mock(OrderPort.class);
        MarketCalendarPort calendar = mock(MarketCalendarPort.class);
        Order locBuy = order("LOC_BUY", null);
        when(orders.findByStrategyId(s.id(), TODAY, TODAY)).thenReturn(List.of(locBuy));
        when(calendar.findSession(TODAY)).thenReturn(Optional.empty());
        assertThat(CloseReportScope.inReportWindow(s, BEFORE_POST_CLOSE, batch, orders, calendar)).isTrue();
        when(calendar.findSession(TODAY)).thenThrow(new IllegalStateException("캘린더가 없습니다."));
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                CloseReportScope.inReportWindow(s, BEFORE_POST_CLOSE, batch, orders, calendar))
                .isInstanceOf(IllegalStateException.class);
    }
}
