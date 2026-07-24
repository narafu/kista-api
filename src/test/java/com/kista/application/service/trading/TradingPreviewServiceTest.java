package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.model.order.NextOrdersPreview.SkipReason;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.port.out.AccountPort;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingPreviewServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock StrategyOrderPlanBuilder planBuilder;
    @Mock TradingBuyCompetitionSimulator competitionSimulator;
    @Mock TradingSellSufficiencySimulator sellSufficiencySimulator;
    @Mock TradingPriceFetcher priceFetcher;

    TradingPreviewService service;

    static final Account ACCOUNT = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());

    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);

    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

    @BeforeEach
    void setUp() {
        service = new TradingPreviewService(accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, competitionSimulator, sellSufficiencySimulator, priceFetcher);
        lenient().when(priceFetcher.fetchPrevCloses(any(), any())).thenReturn(Map.of());
        lenient().when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT.id(), ACCOUNT.userId())).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void preview_returnsOrdersWithoutCompetition_whenPlanHasNoBuyOrders() {
        Order sellOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.orders()).hasSize(1);
        assertThat(result.competition()).isNull();
        verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void preview_returnsSellSufficiencyNull_whenPlanHasNoSellOrders() {
        Order buyOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 5, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(buyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.sellSufficiency()).isNull();
        verify(sellSufficiencySimulator, never()).simulate(any(), any(), any(), any());
    }

    @Test
    void preview_callsSellSufficiencySimulator_whenPlanHasSellOrders() {
        Order sellOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        com.kista.domain.model.order.SellSufficiencyPreview sellSufficiency =
                new com.kista.domain.model.order.SellSufficiencyPreview(false, 2, 0, 3, false);
        when(sellSufficiencySimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(List.of(sellOrder)), any()))
                .thenReturn(sellSufficiency);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.sellSufficiency()).isSameAs(sellSufficiency);
    }

    // 회귀 테스트 — INFINITE AT_OPEN SELL이 오늘 이미 접수(PLACED)됐는데, planBuilder가 매번 처음부터
    // 재계산하는 특성상 동일 SELL을 다시 제시한다. 기존 주문과 겹치는 슬롯은 신규 필요분에서 제외해야
    // sellSufficiencySimulator가 "이미 접수된 수량 + 그걸 다시 계산한 수량"을 이중으로 합산하지 않는다.
    @Test
    void preview_excludesAlreadyPlacedSellLeg_fromSellSufficiencyRequiredQuantity() {
        Order existingSell = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 22, new BigDecimal("60.00"), Order.OrderTiming.AT_OPEN);
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingSell));

        Order recomputedSell = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 22, new BigDecimal("60.00"), Order.OrderTiming.AT_OPEN);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(recomputedSell));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.sellSufficiency()).isNull();
        verify(sellSufficiencySimulator, never()).simulate(any(), any(), any(), any());
    }

    // 일부만 이미 접수된 경우 — 신규 leg만 sellSufficiencySimulator에 전달돼야 한다
    @Test
    void preview_passesOnlyNewSellLeg_whenPlanHasBothExistingAndNewSellOrders() {
        Order existingSell = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 22, new BigDecimal("60.00"), Order.OrderTiming.AT_OPEN, "LEG_A");
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingSell));

        Order sameRecomputedSell = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 22, new BigDecimal("60.00"), Order.OrderTiming.AT_OPEN, "LEG_A");
        Order newSell = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 5, new BigDecimal("61.00"), Order.OrderTiming.AT_OPEN, "LEG_B");
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sameRecomputedSell, newSell));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        com.kista.domain.model.order.SellSufficiencyPreview sellSufficiency =
                new com.kista.domain.model.order.SellSufficiencyPreview(true, 30, 22, 5, false);
        when(sellSufficiencySimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(List.of(newSell)), any()))
                .thenReturn(sellSufficiency);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.sellSufficiency()).isSameAs(sellSufficiency);
    }

    @Test
    void preview_callsCompetitionSimulator_whenPlanHasBuyOrders() {
        Order buyOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 5, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(buyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of(), false);
        when(competitionSimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(buyOrder)), any(), eq(BigDecimal.ZERO)))
                .thenReturn(competition);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.competition()).isSameAs(competition);
    }

    @Test
    void preview_propagatesNonZeroOtherStrategiesPlannedBuyUsd_toSimulator() {
        // 이 전략의 사이클에 이미 존재하는 당일 PLANNED BUY (수량 5 @ 10.00 = 50.00)
        Order existingBuyOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 5, new BigDecimal("10.00"));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(eq(STRATEGY_CYCLE.id()), any()))
                .thenReturn(List.of(existingBuyOrder));
        // 계좌 전체 당일 PLANNED BUY 합계 300.00 (타 전략분 포함)
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any()))
                .thenReturn(new BigDecimal("300.00"));

        Order newBuyOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 2, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(newBuyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of(), false);
        // 300.00(계좌 전체) - 50.00(이 전략분) = 250.00(타 전략분)이 그대로 전파되는지 검증
        when(competitionSimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(newBuyOrder)), any(), eq(new BigDecimal("250.00"))))
                .thenReturn(competition);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.competition()).isSameAs(competition);
        verify(competitionSimulator).simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(newBuyOrder)), any(), eq(new BigDecimal("250.00")));
    }

    @Test
    void preview_returnsSkip_whenPlanBuilderSkips() {
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(null, SkipReason.NO_CYCLE_HISTORY));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        assertThat(result.orders()).isEmpty();
        assertThat(result.competition()).isNull();
        verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any());
    }

    @Test
    void preview_throwsSecurityException_whenNotOwner() {
        UUID otherId = UUID.randomUUID();
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), otherId)).thenThrow(new SecurityException());

        assertThatThrownBy(() -> service.preview(STRATEGY.id(), otherId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void preview_throwsNoSuchElementException_whenStrategyNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(strategyPort.findByIdOrThrow(unknownId))
                .thenThrow(new NoSuchElementException("전략 없음: " + unknownId));

        assertThatThrownBy(() -> service.preview(unknownId, ACCOUNT.userId()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void previewBatch_returnsPreviewPerStrategy_keyedByStrategyId() {
        Order sellOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        Map<UUID, NextOrdersPreview> result = service.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result).containsOnlyKeys(STRATEGY.id());
        assertThat(result.get(STRATEGY.id()).orders()).hasSize(1);
    }

    @Test
    void previewBatch_omitsStrategy_whenNoCycleHistory() {
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY));
        when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.empty());

        Map<UUID, NextOrdersPreview> result = service.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result).isEmpty();
    }

    @Test
    void previewBatch_fetchesPrevClosesOnceInBulk_andPassesCacheToPlanBuilder() {
        Order sellOrder = Order.planned(LocalDate.now(), Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY));
        Map<Ticker, BigDecimal> prevCloseCache = Map.of(Ticker.SOXL, new BigDecimal("22.00"));
        when(priceFetcher.fetchPrevCloses(List.of(Ticker.SOXL), ACCOUNT)).thenReturn(prevCloseCache);
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), eq(prevCloseCache)))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        service.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        verify(priceFetcher, times(1)).fetchPrevCloses(List.of(Ticker.SOXL), ACCOUNT);
        verify(planBuilder).build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), eq(prevCloseCache));
    }

    // 회귀 테스트 — accountId+today로만 결정되는 계좌 전체 당일 PLANNED BUY 합계 조회가
    // 대상 전략 개수만큼 반복 실행되지 않고 previewBatch()에서 1회만 조회돼야 한다
    @Test
    void previewBatch_callsSumPlannedBuyByAccountAndDateOnce_regardlessOfStrategyCount() {
        Strategy s1 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        Strategy s2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        List<Strategy> strategies = List.of(s1, s2);
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(strategies);

        for (Strategy s : strategies) {
            StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), s.id(), UUID.randomUUID(),
                    new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
            when(strategyCyclePort.findLatestByStrategyId(s.id())).thenReturn(Optional.of(cycle));
            Order sellOrder = Order.planned(LocalDate.now(), s.ticker(), Order.OrderType.LIMIT,
                    Order.OrderDirection.SELL, 3, new BigDecimal("25.00"));
            CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(sellOrder));
            when(planBuilder.build(eq(s), eq(ACCOUNT), eq(cycle), any(), anyString(), any()))
                    .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        }

        service.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        verify(orderPort, times(1)).sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any());
    }

    @Test
    void previewBatch_throwsSecurityException_whenNotOwner() {
        UUID otherId = UUID.randomUUID();
        when(accountPort.requireOwnedAccount(ACCOUNT.id(), otherId)).thenThrow(new SecurityException());

        assertThatThrownBy(() -> service.previewBatch(ACCOUNT.id(), otherId))
                .isInstanceOf(SecurityException.class);
    }

    // 회귀 테스트 — 리팩토링 전에는 대상 전략 N개를 순회할 때마다 TradingBuyCompetitionSimulator가
    // 계좌 내 다른 전략 전체를 처음부터 다시 계산해 planBuilder.build()가 O(N²)로 호출됐다
    // (전략 3개 기준 최대 9회). 전략별 계산을 1회로 캐싱한 뒤에는 전략당 정확히 1회씩, 총 N회만 호출돼야 한다.
    @Test
    void previewBatch_callsPlanBuilderBuildOncePerStrategy_evenWithCrossCompetition() {
        Strategy s1 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        Strategy s2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        Strategy s3 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
        List<Strategy> strategies = List.of(s1, s2, s3);
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(strategies);

        Map<UUID, StrategyCycle> cycles = new java.util.HashMap<>();
        for (Strategy s : strategies) {
            StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), s.id(), UUID.randomUUID(),
                    new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
            cycles.put(s.id(), cycle);
            when(strategyCyclePort.findLatestByStrategyId(s.id())).thenReturn(Optional.of(cycle));

            Order buy = Order.planned(LocalDate.now(), s.ticker(), Order.OrderType.LOC,
                    Order.OrderDirection.BUY, 1, new BigDecimal("10.00"));
            CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, List.of(buy));
            when(planBuilder.build(eq(s), eq(ACCOUNT), eq(cycle), any(), anyString(), any()))
                    .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        }

        PreviewDepositCache depositCache = mock(PreviewDepositCache.class);
        lenient().when(depositCache.getUsdDeposit(any(), any())).thenReturn(new BigDecimal("10000.00"));
        com.kista.domain.strategy.CycleOrderStrategies cycleOrderStrategies = mock(com.kista.domain.strategy.CycleOrderStrategies.class);
        com.kista.domain.strategy.CycleOrderStrategy orderStrategy = mock(com.kista.domain.strategy.CycleOrderStrategy.class);
        lenient().when(cycleOrderStrategies.of(any(Strategy.Type.class))).thenReturn(orderStrategy);
        lenient().when(cycleOrderStrategies.of(any(Strategy.class))).thenReturn(orderStrategy);
        lenient().when(orderStrategy.allocationPriority()).thenReturn(1);

        TradingBuyCompetitionSimulator realSimulator = new TradingBuyCompetitionSimulator(
                strategyPort, strategyCyclePort, orderPort, planBuilder, cycleOrderStrategies, depositCache);
        TradingPreviewService realService = new TradingPreviewService(
                accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, realSimulator, sellSufficiencySimulator, priceFetcher);

        realService.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        for (Strategy s : strategies) {
            verify(planBuilder, times(1)).build(eq(s), eq(ACCOUNT), eq(cycles.get(s.id())), any(), anyString(), any());
        }
    }
}
