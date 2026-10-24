package com.kista.tradingcore.trading.application.service;

import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import com.kista.tradingcore.trading.domain.model.NextOrdersPreview;
import com.kista.tradingcore.trading.domain.model.NextOrdersPreview.SkipReason;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderDirection;
import com.kista.tradingcore.trading.domain.model.DcaManualExecutionUnsupportedException;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import com.kista.tradingcore.support.TradingFixtures;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyCycleSeedType;

@ExtendWith(MockitoExtension.class)
class TradingPreviewServiceTest {

    @Mock AccountPort accountPort;
    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock StrategyOrderPlanBuilder planBuilder;
    @Mock StrategyLedgerBudget ledgerBudget;
    @Mock TradingPriceFetcher priceFetcher;

    TradingPreviewService service;

    static final Account ACCOUNT = TradingFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    static final TradingAccount TRADING_ACCOUNT = TradingAccount.from(ACCOUNT); // 서비스가 소유권 검증 후 변환해 넘기는 투영

    static final Strategy STRATEGY = new Strategy(
            UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
            StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);

    // startDate는 항상 과거로 고정 — LocalDate.now()를 쓰면 KST 00:00~04:30 사이 테스트 실행 시
    // DstInfo.nextTradeDate()가 오늘 날짜를 반환해 신규 SCHEDULED_START_NOT_REACHED skip과 경합하는 flaky 테스트가 됨
    static final StrategyCycle STRATEGY_CYCLE = new StrategyCycle(
            UUID.randomUUID(), STRATEGY.id(), UUID.randomUUID(), new BigDecimal("1000.00"), null, LocalDate.now().minusDays(7), null, null, null);

    @BeforeEach
    void setUp() {
        // 생성자에 브로커 잔고·판매가능수량 포트가 없다 — 미리보기는 브로커를 조회하지 않음을 컴파일 타임에 고정
        service = new TradingPreviewService(accountPort, strategyPort, strategyCyclePort, orderPort, planBuilder, ledgerBudget, priceFetcher);
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
    void preview_returnsNoLedgerCheck_whenPlanHasNoBuyOrders() {
        PlannedOrder sellOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.orders()).hasSize(1);
        assertThat(result.ledgerCheck()).isNull();
    }

    // 배치 통합 이후에도 이미 오늘 주문을 낸 경쟁 전략은 경쟁 순위에서 제외돼야 한다(계산은 더 하지만 결과는 불변)
 
    // previewBatch()의 사전계산 단계에서 대상 전략 자신의 build()가 한 번 실패해도(캐시 미스),
    // buildPreview 호출 전에 재시도해야 한다 — 재시도 없이 null을 그대로 넘기면 result.isSkip()에서 NPE.
    @Test
    void preview_retriesOwnPlanComputation_whenPrecomputeFailedForTargetStrategy() {
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE));
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any())).thenReturn(Map.of());

        CycleOrderStrategy.OrderPlan noOrderPlan = new CycleOrderStrategy.OrderPlan(null, null, List.of());
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenThrow(new RuntimeException("일시적 계산 오류"))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(noOrderPlan, null));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result).isNotNull();
        assertThat(result.orders()).isEmpty();
        verify(planBuilder, times(2)).build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any());
    }

    // 최종 리뷰 Finding 1 회귀 테스트 — previewBatch()의 사전계산 재시도 실패가 계좌 내 다른(형제)
    // 전략까지 전파돼 preview(단건) 호출 전체를 실패시키던 문제를 막는다. 형제 전략의 build()가
    // 사전계산·재시도 양쪽 모두 지속적으로 실패해도(thenReturn 없이 항상 throw), 대상 전략의
    // 정상 조회는 영향받지 않아야 한다.
    @Test
    void preview_isolatesTargetFromSiblingStrategyThatPersistentlyFailsComputation() {
        Strategy sibling = new Strategy(
                UUID.randomUUID(), ACCOUNT.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE);
        StrategyCycle siblingCycle = new StrategyCycle(
                UUID.randomUUID(), sibling.id(), UUID.randomUUID(), new BigDecimal("1000.00"),
                null, LocalDate.now().minusDays(7), null, null, null);

        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STRATEGY, sibling));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id(), sibling.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE, sibling.id(), siblingCycle));

        PlannedOrder sellOrder = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LIMIT,
                OrderDirection.SELL, 3, new BigDecimal("25.00"));
        CycleOrderStrategy.OrderPlan targetPlan = new CycleOrderStrategy.OrderPlan(null, null, List.of(sellOrder));
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(targetPlan, null));
        // 형제 전략은 사전계산·재시도 양쪽 모두 지속적으로 실패한다 — thenReturn 없이 항상 throw
        when(planBuilder.build(eq(sibling), eq(TRADING_ACCOUNT), eq(siblingCycle), any(), anyString(), any()))
                .thenThrow(new RuntimeException("지속적 계산 오류"));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result).isNotNull();
        assertThat(result.orders()).hasSize(1);
        // 형제 전략은 사전계산 1회 + 최종 루프 재시도 1회, 총 2회 시도된다
        verify(planBuilder, times(2)).build(eq(sibling), eq(TRADING_ACCOUNT), eq(siblingCycle), any(), anyString(), any());
    }

    // 대상 전략 자체의 계산이 지속 실패하면 404(활성 사이클 없음)로 가리지 않고 원래 예외를 전파해야 한다
    // (증권사 조회 실패가 "사이클 없음"으로 오인되던 회귀 방지)
    @Test
    void preview_propagatesTargetComputationFailure_insteadOfNotFound() {
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STRATEGY.id())))
                .thenReturn(Map.of(STRATEGY.id(), STRATEGY_CYCLE));
        IllegalStateException brokerFailure = new IllegalStateException("증권사 API 조회에 실패했습니다");
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenThrow(brokerFailure);

        assertThatThrownBy(() -> service.preview(STRATEGY.id(), ACCOUNT.userId()))
                .isSameAs(brokerFailure);
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
    void preview_returnsSkip_whenPlanBuilderSkips() {
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(null, SkipReason.NO_CYCLE_HISTORY));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        assertThat(result.orders()).isEmpty();
        assertThat(result.ledgerCheck()).isNull();
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
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
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
        when(priceFetcher.fetchPrevCloses(List.of(StrategyTicker.SOXL), TRADING_ACCOUNT.brokerRef())).thenReturn(prevCloseCache);
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), eq(prevCloseCache)))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));

        service.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        verify(priceFetcher, times(1)).fetchPrevCloses(List.of(StrategyTicker.SOXL), TRADING_ACCOUNT.brokerRef());
        verify(planBuilder).build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), eq(prevCloseCache));
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
            when(planBuilder.build(eq(s), eq(TRADING_ACCOUNT), eq(cycle), any(), anyString(), any()))
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

    // 회귀 테스트 — 전략별 계산은 1회로 캐싱돼 전략당 정확히 1회씩, 총 N회만 호출돼야 한다
    @Test
    void previewBatch_callsPlanBuilderBuildOncePerStrategy() {
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
            when(planBuilder.build(eq(s), eq(TRADING_ACCOUNT), eq(cycle), any(), anyString(), any()))
                    .thenReturn(new StrategyOrderPlanBuilder.PlanResult(plan, null));
        }
        when(strategyCyclePort.findLatestByStrategyIds(List.of(s1.id(), s2.id(), s3.id()))).thenReturn(cycles);

        TradingPreviewService realService = service;

        realService.previewBatch(ACCOUNT.id(), ACCOUNT.userId());

        for (Strategy s : strategies) {
            verify(planBuilder, times(1)).build(eq(s), eq(TRADING_ACCOUNT), eq(cycles.get(s.id())), any(), anyString(), any());
        }
    }

    // 원장 잔여(177.61)보다 BUY 필요액(304.32)이 크면 경고 — 배치와 같은 StrategyLedgerBudget 호출 결과를 그대로 노출
    @Test
    void privacyOverLedgerShowsInsufficient() {
        PlannedOrder buy = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 8, new BigDecimal("38.04"));
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(new CycleOrderStrategy.OrderPlan(null, null, List.of(buy)), null));
        when(ledgerBudget.applies(StrategyType.INFINITE)).thenReturn(true);
        when(ledgerBudget.remaining(eq(STRATEGY_CYCLE), any())).thenReturn(new BigDecimal("177.61"));
        when(ledgerBudget.fit(eq(StrategyType.INFINITE), eq(List.of(buy)), eq(new BigDecimal("177.61"))))
                .thenReturn(new StrategyLedgerBudget.LedgerFit(List.of(), new BigDecimal("304.32"), false, true));

        NextOrdersPreview result = service.preview(STRATEGY.id(), ACCOUNT.userId());

        assertThat(result.ledgerCheck().sufficient()).isFalse();
        assertThat(result.ledgerCheck().requiredBuyUsd()).isEqualByComparingTo("304.32");
        assertThat(result.ledgerCheck().remainingLedger()).isEqualByComparingTo("177.61");
    }

    @Test
    void dcaHasNoLedgerCheck() {
        PlannedOrder buy = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 1, new BigDecimal("10.00"));
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(new CycleOrderStrategy.OrderPlan(null, null, List.of(buy)), null));
        when(ledgerBudget.applies(StrategyType.INFINITE)).thenReturn(false);

        assertThat(service.preview(STRATEGY.id(), ACCOUNT.userId()).ledgerCheck()).isNull();
        verify(ledgerBudget, never()).fit(any(), any(), any());
    }

    // 이미 저장된 BUY 슬롯은 신규 필요액에서 제외해 원장 잔여(이미 차감됨)와 이중 계산하지 않는다
    @Test
    void alreadyPlacedBuyIsNotCountedAgain() {
        PlannedOrder buy = PlannedOrder.of(LocalDate.now(), StrategyTicker.SOXL, OrderType.LOC,
                OrderDirection.BUY, 1, new BigDecimal("10.00"));
        Order existing = Order.fromPlanned(buy, null, null);
        when(orderPort.findPlannedOrPlacedByCycleIdsAndDate(any(), any())).thenReturn(Map.of(STRATEGY_CYCLE.id(), List.of(existing)));
        when(planBuilder.build(eq(STRATEGY), eq(TRADING_ACCOUNT), eq(STRATEGY_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(new CycleOrderStrategy.OrderPlan(null, null, List.of(buy)), null));
        lenient().when(ledgerBudget.applies(StrategyType.INFINITE)).thenReturn(true);

        assertThat(service.preview(STRATEGY.id(), ACCOUNT.userId()).ledgerCheck()).isNull();
        verify(ledgerBudget, never()).fit(any(), any(), any());
    }

    static final Strategy STOCK_DCA = new Strategy(UUID.randomUUID(), ACCOUNT.id(), StrategyType.DCA,
            StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
    static final StrategyCycle DCA_CYCLE = new StrategyCycle(UUID.randomUUID(), STOCK_DCA.id(), UUID.randomUUID(),
            BigDecimal.ZERO, null, LocalDate.now().minusDays(7), null, null, null);
    static final String DCA_KEY = "11111111-1111-1111-1111-111111111111";

    private void stubDcaAccount() {
        when(strategyPort.findByIdOrThrow(STOCK_DCA.id())).thenReturn(STOCK_DCA);
        when(strategyPort.findByAccountId(ACCOUNT.id())).thenReturn(List.of(STOCK_DCA));
        when(strategyCyclePort.findLatestByStrategyIds(List.of(STOCK_DCA.id()))).thenReturn(Map.of(STOCK_DCA.id(), DCA_CYCLE));
    }

    @Test
    void DCA_주식_전략도_프리뷰에_포함() {
        stubDcaAccount();
        PlannedOrder dcaBuy = PlannedOrder.of(LocalDate.now(), StrategyTicker.UPRO, OrderType.MOC, OrderDirection.BUY, 3,
                new BigDecimal("91.48"), OrderTiming.AT_CLOSE, "DCA_" + DCA_KEY);
        when(planBuilder.build(eq(STOCK_DCA), eq(TRADING_ACCOUNT), eq(DCA_CYCLE), any(), anyString(), any()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(new CycleOrderStrategy.OrderPlan(null, null, List.of(dcaBuy)), null));

        NextOrdersPreview result = service.preview(STOCK_DCA.id(), ACCOUNT.userId());

        assertThat(result.skipReason()).isNull();
        assertThat(result.orders()).containsExactly(dcaBuy);
    }

    @Test
    void 프리뷰는_DCA_이력을_쓰지_않는다() {
        // 실제 planBuilder → CycleOrderComputer → DcaPlanInputCollector → DCA 커널 경로. 수집기는 이력 기록 의존이 없고,
        // 판정 완료 여부 조회(find)만 읽는다 — insertIfAbsent(기록)는 한 번도 호출되지 않아야 한다
        stubDcaAccount();
        LocalDate usSession = com.kista.tradingcore.trading.domain.model.DstInfo.nextTradeDate().minusDays(1);
        var calendar = mock(com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort.class);
        when(calendar.usSessionDateOf(any())).thenReturn(usSession);
        when(calendar.isUsSessionOpen(any())).thenReturn(true);
        var candlePort = mock(com.kista.tradingcore.broker.application.port.output.CandlePort.class);
        when(candlePort.getDailyCandlesBefore(eq("UPRO"), eq(usSession), anyInt())).thenReturn(List.of(
                new com.kista.tradingcore.broker.domain.model.BrokerCandle(usSession.minusDays(1), null, null, null, new BigDecimal("87.12"), 0)));
        var dcaDetailPort = mock(com.kista.tradingcore.trading.application.port.output.StrategyDcaDetailPort.class);
        when(dcaDetailPort.findActiveByStrategyId(STOCK_DCA.id())).thenReturn(Optional.of(TradingFixtures.dcaConfig(
                TradingFixtures.stockDcaRule(DCA_KEY, OrderDirection.BUY, "300", OrderType.MOC))));
        var executionPort = mock(com.kista.tradingcore.trading.application.port.output.DcaExecutionPort.class);
        DcaPlanInputCollector collector = new DcaPlanInputCollector(calendar, candlePort,
                mock(com.kista.tradingcore.broker.application.port.output.SellableQuantityPort.class),
                mock(com.kista.tradingcore.trading.application.port.output.FearGreedIndexPort.class), orderPort, dcaDetailPort, executionPort);
        var strategies = new com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies(
                List.of(new com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy()));
        var balanceLoader = mock(com.kista.tradingcore.trading.application.service.support.TradingBalanceLoader.class);
        when(balanceLoader.tryLoadBalance(STOCK_DCA)).thenReturn(new com.kista.tradingcore.trading.application.service.support.TradingBalanceLoader.BalanceLoad(
                new com.kista.tradingcore.matching.domain.model.AccountBalance(0, null, BigDecimal.ZERO), null));
        StrategyOrderPlanBuilder realBuilder = new StrategyOrderPlanBuilder(balanceLoader, null, null,
                new CycleOrderComputer(strategies, null, null, null, null, null, orderPort), strategies, null, orderPort, collector);
        TradingPreviewService wired = new TradingPreviewService(accountPort, strategyPort, strategyCyclePort, orderPort, realBuilder,
                ledgerBudget, priceFetcher);

        NextOrdersPreview result = wired.preview(STOCK_DCA.id(), ACCOUNT.userId());

        assertThat(result.orders()).extracting(PlannedOrder::orderLeg).containsExactly("DCA_" + DCA_KEY);
        verify(executionPort, never()).insertIfAbsent(any());
    }

    @Test
    void 코인_DCA는_단건_프리뷰를_지원하지_않는다() {
        Account upbit = TradingFixtures.upbitAccount(UUID.randomUUID(), UUID.randomUUID());
        Strategy btc = new Strategy(UUID.randomUUID(), upbit.id(), StrategyType.DCA,
                StrategyStatus.ACTIVE, StrategyTicker.BTC, StrategyCycleSeedType.NONE);
        when(strategyPort.findByIdOrThrow(btc.id())).thenReturn(btc);
        when(accountPort.requireOwnedAccount(upbit.id(), upbit.userId())).thenReturn(upbit);

        assertThatThrownBy(() -> service.preview(btc.id(), upbit.userId()))
                .isInstanceOf(DcaManualExecutionUnsupportedException.class);
    }

    @Test
    void previewBatch_cryptoAccount_returnsEmptyWithoutBrokerCalls() {
        // 코인 계좌 전략은 전부 DCA — 미리보기 대상이 아니라 브로커 라우터(시세 조회)를 타지 않는다
        Account upbit = TradingFixtures.upbitAccount(UUID.randomUUID(), UUID.randomUUID());
        when(accountPort.requireOwnedAccount(upbit.id(), upbit.userId())).thenReturn(upbit);

        assertThat(service.previewBatch(upbit.id(), upbit.userId())).isEmpty();
        verifyNoInteractions(priceFetcher, planBuilder);
    }
}
