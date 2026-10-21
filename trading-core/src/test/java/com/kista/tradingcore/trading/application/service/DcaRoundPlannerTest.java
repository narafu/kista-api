package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
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
import com.kista.tradingcore.broker.application.port.output.SellableQuantityPort;
import com.kista.tradingcore.broker.domain.model.BrokerCandle;
import com.kista.tradingcore.broker.domain.model.SellableQuantity;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
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

import static com.kista.tradingcore.support.TradingFixtures.stockDcaRule;
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
    FearGreedIndexPort fearGreedPort = mock(FearGreedIndexPort.class);
    OrderPort orderPort = mock(OrderPort.class);
    StrategyDcaDetailPort dcaDetailPort = mock(StrategyDcaDetailPort.class);
    DcaExecutionPort executionPort = mock(DcaExecutionPort.class);
    DcaExecutionRecorder recorder = mock(DcaExecutionRecorder.class);
    DcaRoundPlanner planner = new DcaRoundPlanner(calendar, candlePort, sellablePort, fearGreedPort, orderPort,
            dcaDetailPort, executionPort, recorder, new DcaCycleOrderStrategy());

    static final String KEY = "11111111-1111-1111-1111-111111111111";
    static final String KEY2 = "22222222-2222-2222-2222-222222222222";

    BatchContext ctx;
    AccountBalance ledger = new AccountBalance(0, null, BigDecimal.ZERO); // 원장(최신 cycle_position) — 기본 보유 없음

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
        when(executionPort.find(any(), any(), eq(TRADE_DATE))).thenReturn(Optional.empty());
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 12), null, null, null, new BigDecimal("87.12"), 0)));
    }

    @Test
    void plans_buy_when_due_and_timing_matches() {
        dca(OrderDirection.BUY, OrderType.MOC);
        List<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);
        assertThat(plan).singleElement().extracting(p -> p.order().quantity()).isEqualTo(3);
        verifyNoInteractions(recorder);
    }

    @Test
    void current_price_above_prev_close_becomes_reference_price() {
        dca(OrderDirection.BUY, OrderType.MOC);
        List<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), new BigDecimal("95.00"), ledger);
        assertThat(plan).singleElement().extracting(DcaDecision.Planned::referencePrice).isEqualTo(new BigDecimal("95.00"));
    }

    @Test
    void ignores_other_timing_batch() {
        dca(OrderDirection.BUY, OrderType.MOO);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void not_execution_day_records_nothing() {
        config(new DcaRule(KEY, OrderDirection.BUY, DcaFrequency.MONTHLY, null, 1, OrderType.MOC, null,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal("300"), null), DcaShortfallPolicy.PARTIAL, List.of()));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void already_decided_today_skips_without_recording() {
        dca(OrderDirection.BUY, OrderType.MOC);
        when(executionPort.find(ctx.strategy().id(), KEY, TRADE_DATE))
                .thenReturn(Optional.of(DcaExecution.skipped(ctx.strategy().id(), KEY, TRADE_DATE, DcaSkipReason.NO_HOLDINGS, null, null)));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();
        verifyNoInteractions(candlePort, recorder);
    }

    @Test
    void existing_dca_order_without_execution_row_recovers_ordered() {
        dca(OrderDirection.BUY, OrderType.MOC);
        Order existing = existingOrder(OrderTiming.AT_CLOSE, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(existing), null, ledger)).isEmpty();
        verify(recorder).recordOrdered(ctx, TRADE_DATE, KEY, existing, null);
        verifyNoInteractions(candlePort); // 재판정(시세 재조회) 없이 복구만
    }

    @Test
    void existing_dca_order_with_execution_row_does_nothing() {
        dca(OrderDirection.BUY, OrderType.MOC);
        when(executionPort.find(ctx.strategy().id(), KEY, TRADE_DATE)).thenReturn(Optional.of(
                DcaExecution.ordered(ctx.strategy().id(), KEY, TRADE_DATE, UUID.randomUUID(), BigDecimal.valueOf(3), null)));
        Order existing = existingOrder(OrderTiming.AT_CLOSE, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(existing), null, ledger)).isEmpty();
        verify(recorder, never()).recordOrdered(any(), any(), any(), any(), any());
    }

    @Test
    void filled_dca_order_without_execution_row_recovers_ordered_and_plans_nothing() {
        // PLANNED/PLACED 조회에는 안 잡히는 종결 상태(FILLED) 주문 — 이력 누락 재진입에서 2번째 주문을 만들면 안 된다
        dca(OrderDirection.BUY, OrderType.MOC);
        Order filled = new Order(UUID.randomUUID(), ctx.account().id(), ctx.currentCycle().id(), TRADE_DATE, StrategyTicker.UPRO,
                OrderType.MOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA_" + KEY, BigDecimal.valueOf(3), new BigDecimal("91.48"),
                OrderStatus.FILLED, "E1", BigDecimal.valueOf(3), new BigDecimal("88.00"));
        when(orderPort.findByStrategyId(ctx.strategy().id(), TRADE_DATE, TRADE_DATE)).thenReturn(List.of(filled));

        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();

        verify(recorder).recordOrdered(ctx, TRADE_DATE, KEY, filled, null);
        verifyNoInteractions(candlePort);
    }

    @Test
    void existing_open_order_is_recovered_even_in_close_batch() {
        // MOO 주문 저장 후 이력 기록 전 중단 — 마감 배치(AT_CLOSE)에서도 ORDERED 복구되어야 이력이 누락되지 않는다
        dca(OrderDirection.BUY, OrderType.MOO);
        Order existing = existingOrder(OrderTiming.AT_OPEN, OrderType.MOO);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(existing), null, ledger)).isEmpty();
        verify(recorder).recordOrdered(ctx, TRADE_DATE, KEY, existing, null);
    }

    @Test
    void candle_failure_records_data_unavailable() {
        dca(OrderDirection.BUY, OrderType.MOC);
        when(candlePort.getDailyCandlesBefore(any(), any(), anyInt())).thenThrow(new RuntimeException("toss down"));
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(KEY), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), isNull());
    }

    @Test
    void sell_subtracts_reserved_quantity() {
        dca(OrderDirection.SELL, OrderType.MOC);
        when(sellablePort.getSellableQuantity(StrategyTicker.UPRO, ctx.account().brokerRef())).thenReturn(new SellableQuantity("UPRO", 5));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(ctx.account().id(), TRADE_DATE, StrategyTicker.UPRO)).thenReturn(5);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(KEY), eq(DcaSkipReason.NO_HOLDINGS), anyString(), any());
    }

    @Test
    void stale_feed_is_data_unavailable() {
        // 직전 개장일은 10-12인데 마지막 봉이 10-09 → 피드 지연
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 9), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();
        verify(recorder).recordSkipped(any(), eq(TRADE_DATE), eq(KEY), eq(DcaSkipReason.DATA_UNAVAILABLE), anyString(), isNull());
    }

    @Test
    void previous_open_session_skips_holidays() {
        // 10-12 휴장·10-10/11 주말 → 직전 개장일 10-09, 마지막 봉 10-09면 정상
        for (int day = 10; day <= 12; day++) when(calendar.isUsSessionOpen(LocalDate.of(2026, 10, day))).thenReturn(false);
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250)).thenReturn(List.of(
                new BrokerCandle(LocalDate.of(2026, 10, 9), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isNotEmpty();
    }

    @Test
    void candles_are_cached_per_ticker_and_session() {
        dca(OrderDirection.BUY, OrderType.MOC);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);
        verify(candlePort, times(1)).getDailyCandlesBefore("UPRO", US_SESSION, 250);
    }

    @Test
    void previous_open_session_looked_up_once_per_session() {
        dca(OrderDirection.BUY, OrderType.MOC);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);
        verify(calendar, times(1)).isUsSessionOpen(PREV_OPEN); // 같은 세션 재계획이 직전 개장일 메모를 공유
    }

    @Test
    void candle_failure_is_not_cached() {
        when(candlePort.getDailyCandlesBefore("UPRO", US_SESSION, 250))
                .thenThrow(new RuntimeException("toss down"))
                .thenReturn(List.of(new BrokerCandle(LocalDate.of(2026, 10, 12), null, null, null, new BigDecimal("87.12"), 0)));
        dca(OrderDirection.BUY, OrderType.MOC);
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isNotEmpty();
    }

    @Test
    void avg_price_condition_uses_ledger_avg_price() {
        dcaWith(OrderDirection.BUY, OrderType.MOC, new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5")));
        ledger = new AccountBalance(10, new BigDecimal("80.00"), BigDecimal.ZERO); // 80×0.95=76 < 87.12 → 미충족
        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();
        verify(recorder).recordSkipped(any(), eq(TRADE_DATE), eq(KEY), eq(DcaSkipReason.CONDITION_NOT_MET), anyString(), any());
    }

    @Test
    void 같은날_ruleKey_보존_편집후_재발동하지_않는다() {
        // 장중 편집으로 새 버전이 됐어도 ruleKey가 같으면 오늘 판정 이력이 그대로 적용된다 — 신규 규칙(KEY2)만 판정
        config(stockDcaRule(KEY, OrderDirection.BUY, "600", OrderType.MOC), stockDcaRule(KEY2, OrderDirection.BUY, "300", OrderType.MOC));
        when(executionPort.find(ctx.strategy().id(), KEY, TRADE_DATE)).thenReturn(Optional.of(
                DcaExecution.ordered(ctx.strategy().id(), KEY, TRADE_DATE, UUID.randomUUID(), BigDecimal.valueOf(3), null)));

        List<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);

        assertThat(plan).singleElement().extracting(p -> p.order().orderLeg()).isEqualTo("DCA_" + KEY2);
    }

    @Test
    void 같은_시점_규칙은_각자_주문하고_매도는_앞_규칙_계획분을_뺀다() {
        config(stockDcaRule(KEY, OrderDirection.SELL, "300", OrderType.MOC),  // 300/87.12 → 3주
                stockDcaRule(KEY2, OrderDirection.SELL, "300", OrderType.MOC),
                stockDcaRule("33333333-3333-3333-3333-333333333333", OrderDirection.BUY, "300", OrderType.MOC));
        ledger = new AccountBalance(10, new BigDecimal("80.00"), BigDecimal.ZERO);
        when(sellablePort.getSellableQuantity(StrategyTicker.UPRO, ctx.account().brokerRef())).thenReturn(new SellableQuantity("UPRO", 5));

        List<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);

        // 판매가능 5 − 앞 규칙 3 = 2 → 두 번째 SELL은 PARTIAL로 2주
        assertThat(plan).extracting(p -> p.order().direction(), p -> p.order().quantity())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(OrderDirection.SELL, 3),
                        org.assertj.core.groups.Tuple.tuple(OrderDirection.SELL, 2),
                        org.assertj.core.groups.Tuple.tuple(OrderDirection.BUY, 3));
    }

    @Test
    void 같은_시점_매도_규칙_합계는_원장_보유를_넘지_않는다() {
        // 원장 10 < 판매가능 100 — 두 PARTIAL SELL(각 8주 희망)은 8 + 2
        config(stockDcaRule(KEY, OrderDirection.SELL, "697", OrderType.MOC),   // 697/87.12 → 8주
                stockDcaRule(KEY2, OrderDirection.SELL, "697", OrderType.MOC));
        ledger = new AccountBalance(10, new BigDecimal("80.00"), BigDecimal.ZERO);
        when(sellablePort.getSellableQuantity(StrategyTicker.UPRO, ctx.account().brokerRef())).thenReturn(new SellableQuantity("UPRO", 100));

        List<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);

        assertThat(plan).extracting(p -> p.order().quantity()).containsExactly(8, 2);
    }

    private void fearGreedDca(String threshold) {
        dcaWith(OrderDirection.BUY, OrderType.MOC, new DcaCondition(DcaIndicator.FEAR_GREED, DcaOperator.LTE, null, new BigDecimal(threshold)));
    }

    @Test
    void fear_greed_condition_uses_latest_value() {
        fearGreedDca("25");
        when(fearGreedPort.latest()).thenReturn(Optional.of(new BigDecimal("20.00")));

        List<DcaDecision.Planned> plan = planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);

        assertThat(plan).isNotEmpty();
        verify(fearGreedPort).latest();
    }

    @Test
    void fear_greed_unavailable_records_data_unavailable() {
        fearGreedDca("25");
        when(fearGreedPort.latest()).thenReturn(Optional.empty());

        assertThat(planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger)).isEmpty();
        verify(recorder).recordSkipped(eq(ctx), eq(TRADE_DATE), eq(KEY), eq(DcaSkipReason.DATA_UNAVAILABLE), eq("공포탐욕지수 조회 실패"), any());
    }

    @Test
    void no_fear_greed_condition_skips_cnn_call() {
        dca(OrderDirection.BUY, OrderType.MOC);

        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger);

        verifyNoInteractions(fearGreedPort);
    }

    @Test
    void fear_greed_memoized_within_ttl_and_failure_not_cached() {
        fearGreedDca("25");
        when(fearGreedPort.latest()).thenReturn(Optional.empty(), Optional.of(new BigDecimal("20.00")));

        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger); // 실패 — 캐시 안 됨
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger); // 재조회 성공 — 캐시
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger); // 캐시 적중

        verify(fearGreedPort, times(2)).latest();
    }

    @Test
    void fear_greed_refetched_after_ttl_expires() {
        fearGreedDca("25");
        when(fearGreedPort.latest()).thenReturn(Optional.of(new BigDecimal("20.00")));
        Instant start = Instant.parse("2026-10-13T19:30:00Z");
        AtomicReference<Instant> now = new AtomicReference<>(start);
        planner.clock = now::get;

        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger); // 조회 — 메모
        now.set(start.plus(Duration.ofMinutes(9)));
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger); // TTL 안 — 메모 적중
        now.set(start.plus(Duration.ofMinutes(10)));
        planner.plan(ctx, TRADE_DATE, EnumSet.of(OrderTiming.AT_CLOSE), List.of(), null, ledger); // TTL 경과 — 재조회

        verify(fearGreedPort, times(2)).latest();
    }

    private Order existingOrder(OrderTiming timing, OrderType type) {
        return new Order(UUID.randomUUID(), ctx.account().id(), ctx.currentCycle().id(), TRADE_DATE, StrategyTicker.UPRO,
                type, timing, OrderDirection.BUY, "DCA_" + KEY, BigDecimal.valueOf(3), new BigDecimal("91.48"), OrderStatus.PLANNED, null, null, null);
    }

    private void config(DcaRule... rules) {
        when(dcaDetailPort.findActiveByStrategyId(ctx.strategy().id())).thenReturn(Optional.of(TradingFixtures.dcaConfig(rules)));
    }

    private void dca(OrderDirection dir, OrderType type) {
        config(stockDcaRule(KEY, dir, "300", type));
    }

    private void dcaWith(OrderDirection dir, OrderType type, DcaCondition... conditions) {
        config(stockDcaRule(KEY, dir, "300", type, conditions));
    }
}
