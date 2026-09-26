package com.kista.trading.application.service;

import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.BuyCompetitionPreview;
import com.kista.trading.domain.model.NextOrdersPreview;
import com.kista.trading.domain.model.NextOrdersPreview.SkipReason;
import com.kista.trading.domain.model.Order;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderTiming;
import com.kista.sharedkernel.OrderDirection;
import com.kista.trading.domain.model.Strategy;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.account.application.port.output.AccountPort;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.trading.application.port.output.StrategyCyclePort;
import com.kista.trading.application.port.output.StrategyPort;
import com.kista.matching.domain.strategy.CycleOrderStrategy;
import com.kista.support.TradingFixtures;
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
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyCycleSeedType;

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

    static final Account ACCOUNT = TradingFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());

    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
            StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

    // startDate는 항상 과거로 고정 — LocalDate.now()를 쓰면 KST 00:00~04:30 사이 테스트 실행 시
    // DstInfo.nextTradeDate()가 오늘 날짜를 반환해 신규 SCHEDULED_START_NOT_REACHED skip과 경합하는 flaky 테스트가 됨
    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now().minusDays(7), null, null, null);

    @BeforeEach
    void setUp() {
        service = new TradingPreviewService(accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, competitionSimulator, sellSufficiencySimulator, priceFetcher);
        lenient().when(priceFetcher.fetchPrevCloses(any(), any())).thenReturn(Map.of());
        lenient().when(strategyPort.findByIdOrThrow(STRATEGY.id())).thenReturn(STRATEGY);
        lenient().when(accountPort.requireOwnedAccount(ACCOUNT.id(), ACCOUNT.userId())).thenReturn(ACCOUNT);
        lenient().when(strategyCyclePort.findLatestByStrategyId(STRATEGY.id())).thenReturn(Optional.of(STRATEGY_CYCLE));
        // preview()는 이제 previewBatch()에 위임한다 — 단건 preview() 테스트도 배치 조회 스텁이 필요
        lenient().when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY));
        lenient().when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE));
        lenient().when(orderPort.findPlannedOrPlacedByCycleAndDate(any(), any())).thenReturn(List.of());
        lenient().when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any())).thenReturn(Map.of());
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    void preview_returnsOrdersWithoutCompetition_whenPlanHasNoBuyOrders() {
        PlannedOrder sellOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.orders()).hasSize(1);
        assertThat(result.competition()).isNull();
        verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any(), any());
    }

    // 배치 통합 이후에도 이미 오늘 주문을 낸 경쟁 전략은 경쟁 순위에서 제외돼야 한다(계산은 더 하지만 결과는 불변)
    @Test
    void preview_excludesCompetitorThatAlreadyOrderedToday_evenThoughBatchAlwaysComputesItsPlan() {
        Strategy competitor = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle competitorCycle = new StrategyCycle(
                UUID.randomUUID(), competitor.id(), UUID.randomUUID(), new BigDecimal("1000.00"),
                null, LocalDate.now().minusDays(7), null, null, null);
        Order competitorExistingOrder = Order.fromPlanned(PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL,
                OrderType.LIMIT, OrderDirection.BUY, 1, new BigDecimal("20.00")), null, null);

        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY, competitor));
        // previewBatch()의 배치 사이클 조회는 대상 전략(STRATEGY) 목록으로만 호출되지 않는다 — 계좌 내 전략 전체를 대상으로 조회
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id(), competitor.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE, competitor.id(), competitorCycle));
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any()))
                .thenReturn(Map.of(competitorCycle.id(), List.of(competitorExistingOrder)));

        PlannedOrder buyOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.BUY, 1, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan targetPlan = new CycleOrderStrategy.OrderPlan(null, null, List.of(buyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(targetPlan, null));
        // 경쟁자는 이미 오늘 주문이 있으므로 previewBatch가 계획을 계산은 하지만(배치는 항상 전량 계산)
        // TradingBuyCompetitionSimulator가 alreadyOrdered로 걸러 최종 경쟁 순위에는 포함하지 않는다.
        // 금액을 target(20.00)보다 의도적으로 작게(10.00) 잡아야 한다 — BuyPriorityOrdering이 동일 타입일 때
        // 금액 오름차순으로 정렬하므로, 제외 로직이 깨지면 더 작은 금액인 competitor가 항상 target보다
        // 먼저 정렬돼 blocked에 반드시 나타난다. 동일 금액이면 tie-break가 랜덤 UUID 비교로 떨어져
        // 회귀를 절반의 확률로만 잡는 결정성 없는 테스트가 된다.
        PlannedOrder competitorBuyOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.BUY, 1, new BigDecimal("10.00"));
        when(planBuilder.build(eq(competitor), eq(ACCOUNT), eq(competitorCycle), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(
                        new CycleOrderStrategy.OrderPlan(null, null, List.of(competitorBuyOrder)), null));

        // competitionSimulator는 @Mock이라 실제 경쟁 로직을 검증하려면 실제 구현체+실제 서비스가 필요
        PreviewDepositCache depositCache = mock(PreviewDepositCache.class);
        lenient().when(depositCache.getUsdDeposit(any(), any())).thenReturn(new BigDecimal("10000.00"));
        com.kista.matching.domain.strategy.CycleOrderStrategies cycleOrderStrategies = mock(com.kista.matching.domain.strategy.CycleOrderStrategies.class);
        com.kista.matching.domain.strategy.CycleOrderStrategy orderStrategy = mock(com.kista.matching.domain.strategy.CycleOrderStrategy.class);
        lenient().when(cycleOrderStrategies.of(any(StrategyType.class))).thenReturn(orderStrategy);
        lenient().when(orderStrategy.allocationPriority()).thenReturn(1);

        TradingBuyCompetitionSimulator realSimulator = new TradingBuyCompetitionSimulator(
                planBuilder, cycleOrderStrategies, depositCache);
        TradingPreviewService realService = new TradingPreviewService(
                accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, realSimulator, sellSufficiencySimulator, priceFetcher);

        NextOrdersPreview result = realService.preview(STRATEGY.id(), ACCOUNT.userId());

        // 배치가 competitor의 계획도 미리 계산했음을 확인(비용 증가는 의도된 트레이드오프)
        verify(planBuilder).build(eq(competitor), eq(ACCOUNT), eq(competitorCycle), any(), anyString(), any());
        // 하지만 competitor는 alreadyOrdered라 경쟁 순위에서 제외돼 blocked 목록에 나타나지 않는다
        assertThat(result.competition()).isNotNull();
        assertThat(result.competition().blockedByHigherPriority()).isEmpty();
    }

    // previewBatch()의 사전계산 단계에서 대상 전략 자신의 build()가 한 번 실패해도(캐시 미스),
    // buildPreview 호출 전에 재시도해야 한다 — 재시도 없이 null을 그대로 넘기면 result.isSkip()에서 NPE.
    @Test
    void preview_retriesOwnPlanComputation_whenPrecomputeFailedForTargetStrategy() {
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE));
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any())).thenReturn(Map.of());

        CycleOrderStrategy.OrderPlan noOrderPlan = new CycleOrderStrategy.OrderPlan(null, null, List.of());
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenThrow(new RuntimeException("일시적 계산 오류"))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(noOrderPlan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result).isNotNull();
        assertThat(result.orders()).isEmpty();
        verify(planBuilder, times(2)).build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any());
    }

    // 시작예정일 미도래 사이클 — TradingService.filterScheduledStart와 동일 기준으로 미리보기도 skip해야 함
    @Test
    void preview_returnsScheduledStartNotReached_whenCycleStartDateIsFuture() {
        // DstInfo.nextTradeDate()는 KST 04:30 이전엔 오늘, 이후엔 내일을 반환하므로 명확히 먼 미래로 고정해야
        // 테스트 실행 시각과 무관하게 항상 시작예정일 미도래로 판정된다
        StrategyCycle futureCycle = new StrategyCycle(
                STRATEGY_CYCLE.id(), STRATEGY.id(), STRATEGY_CYCLE.strategyVersionId(), new BigDecimal("1000.00"),
                null, LocalDate.now().plusDays(30), null, null, null);
        // preview()는 previewBatch()에 위임 — 배치 조회(findLatestByStrategyIds)로 사이클을 얻는다
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id())))
                .thenReturn(Map.of(STRATEGY.id(), futureCycle));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(NextOrdersPreview.SkipReason.SCHEDULED_START_NOT_REACHED);
        assertThat(result.orders()).isEmpty();
        verify(planBuilder, never()).build(any(), any(), any(), any(), anyString(), any());
    }

    @Test
    void preview_returnsSellSufficiencyNull_whenPlanHasNoSellOrders() {
        PlannedOrder buyOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 5, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(buyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.sellSufficiency()).isNull();
        verify(sellSufficiencySimulator, never()).simulate(any(), any(), any(), any());
    }

    @Test
    void preview_callsSellSufficiencySimulator_whenPlanHasSellOrders() {
        PlannedOrder sellOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        com.kista.trading.domain.model.SellSufficiencyPreview sellSufficiency =
                new com.kista.trading.domain.model.SellSufficiencyPreview(false, 2, 0, 3, false);
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
        Order existingSell = Order.fromPlanned(PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 22, new BigDecimal("60.00"), OrderTiming.AT_OPEN), null, null);
        // previewBatch()는 배치 조회(findPlannedOrPlacedByCycleIdsAndDate)로 당일 주문을 얻는다
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any()))
                .thenReturn(Map.of(STRATEGY_CYCLE.id(), List.of(existingSell)));

        PlannedOrder recomputedSell = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 22, new BigDecimal("60.00"), OrderTiming.AT_OPEN);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(recomputedSell));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.sellSufficiency()).isNull();
        verify(sellSufficiencySimulator, never()).simulate(any(), any(), any(), any());
    }

    // 일부만 이미 접수된 경우 — 신규 leg만 sellSufficiencySimulator에 전달돼야 한다
    @Test
    void preview_passesOnlyNewSellLeg_whenPlanHasBothExistingAndNewSellOrders() {
        Order existingSell = Order.fromPlanned(PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 22, new BigDecimal("60.00"), OrderTiming.AT_OPEN, "LEG_A"), null, null);
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any()))
                .thenReturn(Map.of(STRATEGY_CYCLE.id(), List.of(existingSell)));

        PlannedOrder sameRecomputedSell = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 22, new BigDecimal("60.00"), OrderTiming.AT_OPEN, "LEG_A");
        PlannedOrder newSell = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 5, new BigDecimal("61.00"), OrderTiming.AT_OPEN, "LEG_B");
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(sameRecomputedSell, newSell));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        com.kista.trading.domain.model.SellSufficiencyPreview sellSufficiency =
                new com.kista.trading.domain.model.SellSufficiencyPreview(true, 30, 22, 5, false);
        when(sellSufficiencySimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(List.of(newSell)), any()))
                .thenReturn(sellSufficiency);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.sellSufficiency()).isSameAs(sellSufficiency);
    }

    @Test
    void preview_callsCompetitionSimulator_whenPlanHasBuyOrders() {
        PlannedOrder buyOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 5, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(buyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of(), false);
        when(competitionSimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(buyOrder)), any(), eq(BigDecimal.ZERO), any()))
                .thenReturn(competition);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.competition()).isSameAs(competition);
    }

    @Test
    void preview_propagatesNonZeroOtherStrategiesPlannedBuyUsd_toSimulator() {
        // 이 전략의 사이클에 이미 존재하는 당일 PLANNED BUY (수량 5 @ 10.00 = 50.00)
        Order existingBuyOrder = Order.fromPlanned(PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 5, new BigDecimal("10.00")), null, null);
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any()))
                .thenReturn(Map.of(STRATEGY_CYCLE.id(), List.of(existingBuyOrder)));
        // 계좌 전체 당일 PLANNED BUY 합계 300.00 (타 전략분 포함)
        when(orderPort.sumPlannedBuyByAccountAndDate(eq(ACCOUNT.id()), any()))
                .thenReturn(new BigDecimal("300.00"));

        PlannedOrder newBuyOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 2, new BigDecimal("20.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(newBuyOrder));
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        BuyCompetitionPreview competition = new BuyCompetitionPreview(
                true, new BigDecimal("1000.00"), new BigDecimal("100.00"), BigDecimal.ZERO, List.of(), List.of(), false);
        // 300.00(계좌 전체) - 50.00(이 전략분) = 250.00(타 전략분)이 그대로 전파되는지 검증
        when(competitionSimulator.simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(newBuyOrder)), any(), eq(new BigDecimal("250.00")), any()))
                .thenReturn(competition);

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.competition()).isSameAs(competition);
        verify(competitionSimulator).simulate(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), eq(List.of(newBuyOrder)), any(), eq(new BigDecimal("250.00")), any());
    }

    @Test
    void preview_returnsSkip_whenPlanBuilderSkips() {
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(null, SkipReason.NO_CYCLE_HISTORY));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        assertThat(result.orders()).isEmpty();
        assertThat(result.competition()).isNull();
        verify(competitionSimulator, never()).simulate(any(), any(), any(), any(), any(), any(), any());
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
        PlannedOrder sellOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(sellOrder));
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
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id()))).thenReturn(Map.of());

        Map<UUID, NextOrdersPreview> result = service.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result).isEmpty();
    }

    @Test
    void previewBatch_fetchesPrevClosesOnceInBulk_andPassesCacheToPlanBuilder() {
        PlannedOrder sellOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(sellOrder));
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY));
        Map<StrategyTicker, BigDecimal> prevCloseCache = Map.of(StrategyTicker.SOXL, new BigDecimal("22.00"));
        when(priceFetcher.fetchPrevCloses(List.of(StrategyTicker.SOXL), ACCOUNT)).thenReturn(prevCloseCache);
        when(planBuilder.build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), eq(prevCloseCache)))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        service.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        verify(priceFetcher, times(1)).fetchPrevCloses(List.of(StrategyTicker.SOXL), ACCOUNT);
        verify(planBuilder).build(eq(STRATEGY), eq(ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), eq(prevCloseCache));
    }

    // 회귀 테스트 — accountId+today로만 결정되는 계좌 전체 당일 PLANNED BUY 합계 조회가
    // 대상 전략 개수만큼 반복 실행되지 않고 previewBatch()에서 1회만 조회돼야 한다
    @Test
    void previewBatch_callsSumPlannedBuyByAccountAndDateOnce_regardlessOfStrategyCount() {
        Strategy s1 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        Strategy s2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        List<Strategy> strategies = List.of(s1, s2);
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(strategies);

        Map<UUID, StrategyCycle> cyclesById = new java.util.HashMap<>();
        for (Strategy s : strategies) {
            // startDate는 과거로 고정 — LocalDate.now()면 KST 00:00~04:30 사이 실행 시 DstInfo.nextTradeDate()가
            // 오늘 날짜를 반환해 SCHEDULED_START_NOT_REACHED skip과 경합하는 flaky 테스트가 됨 (58번째 줄 주석 참고)
            StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), s.id(), UUID.randomUUID(),
                    new BigDecimal("1000.00"), null, LocalDate.now().minusDays(1), null, null, null);
            cyclesById.put(s.id(), cycle);
            PlannedOrder sellOrder = PlannedOrder.of(LocalDate.now(), s.ticker(), OrderType.LIMIT,
                    OrderDirection.SELL, 3, new BigDecimal("25.00"));
            CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(sellOrder));
            when(planBuilder.build(eq(s), eq(ACCOUNT), eq(cycle), any(), anyString(), any()))
                    .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        }
        when(strategyCyclePort.findLatestByStrategyIds(List.of(s1.id(), s2.id()))).thenReturn(cyclesById);

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
        Strategy s1 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        Strategy s2 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        Strategy s3 = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        List<Strategy> strategies = List.of(s1, s2, s3);
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(strategies);

        Map<UUID, StrategyCycle> cycles = new java.util.HashMap<>();
        for (Strategy s : strategies) {
            // startDate는 과거로 고정 — 위 previewBatch_callsSumPlannedBuyByAccountAndDateOnce...와 동일한 이유
            StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), s.id(), UUID.randomUUID(),
                    new BigDecimal("1000.00"), null, LocalDate.now().minusDays(1), null, null, null);
            cycles.put(s.id(), cycle);

            PlannedOrder buy = PlannedOrder.of(LocalDate.now(), s.ticker(), OrderType.LOC,
                    OrderDirection.BUY, 1, new BigDecimal("10.00"));
            CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(buy));
            when(planBuilder.build(eq(s), eq(ACCOUNT), eq(cycle), any(), anyString(), any()))
                    .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        }
        when(strategyCyclePort.findLatestByStrategyIds(List.of(s1.id(), s2.id(), s3.id()))).thenReturn(cycles);

        PreviewDepositCache depositCache = mock(PreviewDepositCache.class);
        lenient().when(depositCache.getUsdDeposit(any(), any())).thenReturn(new BigDecimal("10000.00"));
        com.kista.matching.domain.strategy.CycleOrderStrategies cycleOrderStrategies = mock(com.kista.matching.domain.strategy.CycleOrderStrategies.class);
        com.kista.matching.domain.strategy.CycleOrderStrategy orderStrategy = mock(com.kista.matching.domain.strategy.CycleOrderStrategy.class);
        lenient().when(cycleOrderStrategies.of(any(StrategyType.class))).thenReturn(orderStrategy);
        lenient().when(orderStrategy.allocationPriority()).thenReturn(1);

        TradingBuyCompetitionSimulator realSimulator = new TradingBuyCompetitionSimulator(
                planBuilder, cycleOrderStrategies, depositCache);
        TradingPreviewService realService = new TradingPreviewService(
                accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, realSimulator, sellSufficiencySimulator, priceFetcher);

        realService.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        for (Strategy s : strategies) {
            verify(planBuilder, times(1)).build(eq(s), eq(ACCOUNT), eq(cycles.get(s.id())), any(), anyString(), any());
        }
    }

    // previewBatch()의 skip 캐시 채움 회귀 테스트 — 시작예정일 미도래 전략을 캐시에서 그냥 생략(continue)하면
    // TradingBuyCompetitionSimulator가 캐시 미스로 오인해 재계산, 정상 주문을 만든 것처럼 예산을 잠식한다.
    // 실제 TradingBuyCompetitionSimulator를 그대로 사용해 대상 전략의 경쟁 결과에 영향이 없는지 검증한다.
    @Test
    void previewBatch_excludesScheduledStartNotReachedStrategy_fromCompetitionBudget() {
        Strategy started = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        Strategy notStarted = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        List<Strategy> strategies = List.of(started, notStarted);
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(strategies);

        StrategyCycle startedCycle = new StrategyCycle(UUID.randomUUID(), started.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now().minusDays(7), null, null, null);
        StrategyCycle notStartedCycle = new StrategyCycle(UUID.randomUUID(), notStarted.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, LocalDate.now().plusDays(30), null, null, null);
        Map<UUID, StrategyCycle> cycles = Map.of(started.id(), startedCycle, notStarted.id(), notStartedCycle);
        when(strategyCyclePort.findLatestByStrategyIds(List.of(started.id(), notStarted.id()))).thenReturn(cycles);

        PlannedOrder buy = PlannedOrder.of(LocalDate.now(), started.ticker(), OrderType.LOC,
                OrderDirection.BUY, 1, new BigDecimal("10.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(buy));
        when(planBuilder.build(eq(started), eq(ACCOUNT), eq(startedCycle), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        PreviewDepositCache depositCache = mock(PreviewDepositCache.class);
        lenient().when(depositCache.getUsdDeposit(any(), any())).thenReturn(new BigDecimal("10000.00"));
        com.kista.matching.domain.strategy.CycleOrderStrategies cycleOrderStrategies = mock(com.kista.matching.domain.strategy.CycleOrderStrategies.class);
        com.kista.matching.domain.strategy.CycleOrderStrategy orderStrategy = mock(com.kista.matching.domain.strategy.CycleOrderStrategy.class);
        lenient().when(cycleOrderStrategies.of(any(StrategyType.class))).thenReturn(orderStrategy);
        lenient().when(orderStrategy.allocationPriority()).thenReturn(1);

        TradingBuyCompetitionSimulator realSimulator = new TradingBuyCompetitionSimulator(
                planBuilder, cycleOrderStrategies, depositCache);
        TradingPreviewService realService = new TradingPreviewService(
                accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, realSimulator, sellSufficiencySimulator, priceFetcher);

        Map<UUID, NextOrdersPreview> result = realService.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        assertThat(result.get(notStarted.id()).skipReason()).isEqualTo(SkipReason.SCHEDULED_START_NOT_REACHED);
        verify(planBuilder, never()).build(eq(notStarted), any(), any(), any(), anyString(), any());

        BuyCompetitionPreview competition = result.get(started.id()).competition();
        assertThat(competition.consumedByHigherPriority()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(competition.blockedByHigherPriority()).isEmpty();
    }
}
