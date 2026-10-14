package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.broker.application.port.output.CandlePort;
import com.kista.tradingcore.broker.application.port.output.LiveBalancePort;
import com.kista.tradingcore.broker.application.port.output.SellableQuantityPort;
import com.kista.tradingcore.broker.domain.model.BrokerBalance;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.SellableQuantity;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.DcaExecutionPort;
import com.kista.tradingcore.trading.application.port.output.FearGreedIndexPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DcaRoundPlannerTest {

    static final LocalDate TRADE_DATE = LocalDate.of(2026, 10, 14); // KST
    static final LocalDate US_SESSION = LocalDate.of(2026, 10, 13);
    static final LocalDate PREV_OPEN = LocalDate.of(2026, 10, 12); // US_SESSION 직전 개장일 (setUp: 모든 날 개장)

    MarketCalendarPort calendar = mock(MarketCalendarPort.class);
    CandlePort candlePort = mock(CandlePort.class);
    SellableQuantityPort sellablePort = mock(SellableQuantityPort.class);
    LiveBalancePort liveBalancePort = mock(LiveBalancePort.class);
    FearGreedIndexPort fearGreedPort = mock(FearGreedIndexPort.class);
    OrderPort orderPort = mock(OrderPort.class);
    StrategyDcaDetailPort dcaDetailPort = mock(StrategyDcaDetailPort.class);
    DcaExecutionPort executionPort = mock(DcaExecutionPort.class);
    DcaExecutionRecorder recorder = mock(DcaExecutionRecorder.class);
    DcaRoundPlanner planner = new DcaRoundPlanner(calendar, candlePort, sellablePort, liveBalancePort, fearGreedPort, orderPort,
            dcaDetailPort, executionPort, recorder, new DcaCycleOrderStrategy());

    BatchContext ctx;

    @BeforeEach
    void setUp() {
        TradingAccount account = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), StrategyType.DCA,
                StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                BigDecimal.ZERO, null, LocalDate.of(2026, 10, 1), null, null, null);
        ctx = new BatchContext(strategy, cycle, account, TradingFixtures.tradingUserProfile(account.userId()));
        when(calendar.usSessionDateOf(TRADE_DATE)).thenReturn(US_SESSION);
        when(calendar.isUsSessionOpen(any())).thenReturn(true);
        when(executionPort.find(any(), eq(TRADE_DATE))).thenReturn(Optional.empty());
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 12), null, null, null, new BigDecimal("87.12"), 0)));
    }

    @Test
    void plans_buy_when_due_and_timing_matches() {
        dca(OrderDirection.BUY, OrderType.MOC);
        Optional<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);
        assertThat(plan).get().extracting(p -> p.order().quantity()).isEqualTo(3);
        verifyNoInteractions(recorder);
    }

    @Test
    void current_price_above_prev_close_becomes_reference_price() {
        dca(OrderDirection.BUY, OrderType.MOC);
        Optional<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(),
                new BigDecimal("95.00"));
        assertThat(plan).get().extracting(DcaDecision.Planned::referencePrice).isEqualTo(new BigDecimal("95.00"));
    }

    @Test
    void ignores_other_timing_batch() {
        dca(OrderDirection.BUY, OrderType.MOO);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void not_execution_day_records_nothing() {
        when(dcaDetailPort.findActiveByStrategyId(ctx.strategy().id())).thenReturn(Optional.of(
                new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.MONTHLY, null, 1)));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void already_decided_today_skips_without_recording() {
        dca(OrderDirection.BUY, OrderType.MOC);
        when(executionPort.find(ctx.strategy().id(), TRADE_DATE))
                .thenReturn(Optional.of(DcaExecution.skipped(ctx.strategy().id(), TRADE_DATE, DcaSkipReason.NO_HOLDINGS, null, null)));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void existing_dca_order_without_execution_row_recovers_ordered() {
        dca(OrderDirection.BUY, OrderType.MOC);
        Order existing = existingOrder(OrderTiming.AT_CLOSE, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(existing), null)).isEmpty();
        verify(recorder).recordOrdered(ctx, TRADE_DATE, existing, null);
        verifyNoInteractions(candlePort); // 재판정(시세 재조회) 없이 복구만
    }

    @Test
    void existing_dca_order_with_execution_row_does_nothing() {
        dca(OrderDirection.BUY, OrderType.MOC);
        when(executionPort.find(ctx.strategy().id(), TRADE_DATE)).thenReturn(Optional.of(
                DcaExecution.ordered(ctx.strategy().id(), TRADE_DATE, UUID.randomUUID(), 3, null)));
        Order existing = existingOrder(OrderTiming.AT_CLOSE, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(existing), null)).isEmpty();
        verify(recorder, never()).recordOrdered(any(), any(), any(), any());
    }

    @Test
    void filled_dca_order_without_execution_row_recovers_ordered_and_plans_nothing() {
        // PLANNED/PLACED 조회에는 안 잡히는 종결 상태(FILLED) 주문 — 이력 누락 재진입에서 2번째 주문을 만들면 안 된다
        dca(OrderDirection.BUY, OrderType.MOC);
        Order filled = new Order(UUID.randomUUID(), ctx.account().id(), ctx.currentCycle().id(), TRADE_DATE, StrategyTicker.UPRO,
                OrderType.MOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA_BUY", 3, new BigDecimal("91.48"),
                OrderStatus.FILLED, "E1", 3, new BigDecimal("88.00"));
        when(orderPort.findByStrategyId(ctx.strategy().id(), TRADE_DATE, TRADE_DATE)).thenReturn(List.of(filled));

        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();

        verify(recorder).recordOrdered(ctx, TRADE_DATE, filled, null);
        verifyNoInteractions(candlePort);
    }

    @Test
    void existing_open_order_is_recovered_even_in_close_batch() {
        // MOO 주문 저장 후 이력 기록 전 중단 — 마감 배치(AT_CLOSE)에서도 ORDERED 복구되어야 이력이 누락되지 않는다
        dca(OrderDirection.BUY, OrderType.MOO);
        Order existing = existingOrder(OrderTiming.AT_OPEN, OrderType.MOO);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(existing), null)).isEmpty();
        verify(recorder).recordOrdered(ctx, TRADE_DATE, existing, null);
    }

    @Test
    void candle_failure_records_data_unavailable() {
        dca(OrderDirection.BUY, OrderType.MOC);
        when(candlePort.getDailyCandlesBefore(any(), any(), anyInt())).thenThrow(new RuntimeException("toss down"));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), isNull());
    }

    @Test
    void sell_subtracts_reserved_quantity() {
        dca(OrderDirection.SELL, OrderType.MOC);
        when(sellablePort.getSellableQuantity(StrategyTicker.UPRO, ctx.account().brokerRef())).thenReturn(new SellableQuantity("UPRO", 5));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(ctx.account().id(), TRADE_DATE, StrategyTicker.UPRO)).thenReturn(5);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(DcaSkipReason.NO_HOLDINGS), anyString(), any());
    }

    @Test
    void stale_feed_is_data_unavailable() {
        // 직전 개장일은 10-12인데 마지막 봉이 10-09 → 피드 지연
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 9), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();
        verify(recorder).recordSkipped(any(), eq(TRADE_DATE), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), isNull());
    }

    @Test
    void previous_open_session_skips_holidays() {
        // 10-12 휴장·10-10/11 주말 → 직전 개장일 10-09, 마지막 봉 10-09면 정상
        for (int day = 10; day <= 12; day++) when(calendar.isUsSessionOpen(LocalDate.of(2026, 10, day))).thenReturn(false);
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 9), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isPresent();
    }

    @Test
    void candles_are_cached_per_ticker_and_session() {
        dca(OrderDirection.BUY, OrderType.MOC);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);
        verify(candlePort, times(1)).getDailyCandlesBefore("UPRO", US_SESSION, 250);
    }

    @Test
    void previous_open_session_looked_up_once_per_session() {
        dca(OrderDirection.BUY, OrderType.MOC);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);
        verify(calendar, times(1)).isUsSessionOpen(PREV_OPEN); // 같은 세션 재계획이 직전 개장일 메모를 공유
    }

    @Test
    void candle_failure_is_not_cached() {
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250))
                .thenThrow(new RuntimeException("toss down"))
                .thenReturn(List.of(new BrokerCandle(LocalDate.of(2026, 10, 12), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isPresent();
    }

    @Test
    void avg_price_condition_loads_live_balance() {
        dcaWith(OrderDirection.BUY, OrderType.MOC, new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5")));
        when(liveBalancePort.getLiveBalance(ctx.account().brokerRef(), StrategyTicker.UPRO))
                .thenReturn(new BrokerBalance(10, new BigDecimal("80.00"), BigDecimal.ZERO)); // 80×0.95=76 < 87.12 → 미충족
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();
        verify(recorder).recordSkipped(any(), eq(TRADE_DATE), eq(DcaSkipReason.CONDITION_NOT_MET), anyString(), any());
    }

    @Test
    void no_avg_price_condition_skips_live_balance() {
        dca(OrderDirection.BUY, OrderType.MOC);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);
        verifyNoInteractions(liveBalancePort);
    }

    @Test
    void live_balance_failure_is_data_unavailable() {
        dcaWith(OrderDirection.BUY, OrderType.MOC, new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5")));
        when(liveBalancePort.getLiveBalance(any(), any())).thenThrow(new RuntimeException("broker down"));
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);
        verify(recorder).recordSkipped(any(), eq(TRADE_DATE), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), any());
    }

    private void fearGreedDca(String threshold) {
        when(dcaDetailPort.findActiveByStrategyId(ctx.strategy().id())).thenReturn(Optional.of(
                new DcaConfig(OrderDirection.BUY, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null,
                        List.of(new DcaCondition(DcaIndicator.FEAR_GREED, DcaOperator.LTE, null, new BigDecimal(threshold))))));
    }

    @Test
    void fear_greed_condition_uses_latest_value() {
        fearGreedDca("25");
        when(fearGreedPort.latest()).thenReturn(Optional.of(new BigDecimal("20.00")));

        Optional<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);

        assertThat(plan).isPresent();
        verify(fearGreedPort).latest();
    }

    @Test
    void fear_greed_unavailable_records_data_unavailable() {
        fearGreedDca("25");
        when(fearGreedPort.latest()).thenReturn(Optional.empty());

        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null)).isEmpty();
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(DcaSkipReason.DATA_UNAVAILABLE), eq("공포탐욕지수 조회 실패"), any());
    }

    @Test
    void no_fear_greed_condition_skips_cnn_call() {
        dca(OrderDirection.BUY, OrderType.MOC);

        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null);

        verifyNoInteractions(fearGreedPort);
    }

    @Test
    void fear_greed_memoized_within_ttl_and_failure_not_cached() {
        fearGreedDca("25");
        when(fearGreedPort.latest()).thenReturn(Optional.empty(), Optional.of(new BigDecimal("20.00")));

        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null); // 실패 — 캐시 안 됨
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null); // 재조회 성공 — 캐시
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null); // 캐시 적중

        verify(fearGreedPort, times(2)).latest();
    }

    @Test
    void fear_greed_refetched_after_ttl_expires() {
        fearGreedDca("25");
        when(fearGreedPort.latest()).thenReturn(Optional.of(new BigDecimal("20.00")));
        Instant start = Instant.parse("2026-10-13T19:30:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(start);
        planner.clock = now::get;

        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null); // 조회 — 메모
        now.set(start.plus(Duration.ofMinutes(9)));
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null); // TTL 안 — 메모 적중
        now.set(start.plus(Duration.ofMinutes(10)));
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null); // TTL 경과 — 재조회

        verify(fearGreedPort, times(2)).latest();
    }

    private Order existingOrder(OrderTiming timing, OrderType type) {
        return new Order(UUID.randomUUID(), ctx.account().id(), ctx.currentCycle().id(), TRADE_DATE, StrategyTicker.UPRO,
                type, timing, OrderDirection.BUY, "DCA_BUY", 3, new BigDecimal("91.48"), OrderStatus.PLANNED, null, null, null);
    }

    private void dca(OrderDirection dir, OrderType type) {
        when(dcaDetailPort.findActiveByStrategyId(ctx.strategy().id())).thenReturn(Optional.of(
                new DcaConfig(dir, new BigDecimal("300"), type, DcaFrequency.DAILY, null, null)));
    }

    private void dcaWith(OrderDirection dir, OrderType type, DcaCondition... conditions) {
        when(dcaDetailPort.findActiveByStrategyId(ctx.strategy().id())).thenReturn(Optional.of(
                new DcaConfig(dir, new BigDecimal("300"), type, DcaFrequency.DAILY, null, null, List.of(conditions))));
    }
}
