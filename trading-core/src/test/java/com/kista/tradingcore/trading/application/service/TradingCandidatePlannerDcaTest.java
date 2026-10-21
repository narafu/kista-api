package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.broker.domain.model.PriceSnapshot;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.service.support.TradingBalanceLoader;
import com.kista.tradingcore.trading.application.service.support.TradingBatchGuard;
import com.kista.tradingcore.trading.application.service.support.TradingOrderPlanner;
import com.kista.tradingcore.trading.application.service.support.TradingParallelRunner;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// DCA 후보의 저장 후 ORDERED 이력 기록 / 예산 거절 시 skip 이력 기록 + 잔고 부족 알림 배제
class TradingCandidatePlannerDcaTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    OrderPort orderPort = mock(OrderPort.class);
    TradingOrderPlanner orderPlanner = mock(TradingOrderPlanner.class);
    TradingOrderBudgetAllocator allocator = mock(TradingOrderBudgetAllocator.class);
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    DcaExecutionRecorder recorder = mock(DcaExecutionRecorder.class);
    TradingCandidatePlanner planner;
    BatchContext ctx;
    PlannedOrder planned;

    @BeforeEach
    void setUp() {
        planner = new TradingCandidatePlanner(orderPort, null, orderPlanner, null, null, allocator, null,
                publisher, new TradingBatchGuard(publisher), new AccountBudgetLock(), mock(DcaRoundPlanner.class), recorder,
                new TradingParallelRunner(0));
        TradingAccount account = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), StrategyType.DCA,
                StrategyStatus.ACTIVE, StrategyTicker.UPRO, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                BigDecimal.ZERO, null, LocalDate.of(2026, 10, 1), null, null, null);
        ctx = new BatchContext(strategy, cycle, account, TradingFixtures.tradingUserProfile(account.userId()));
        planned = PlannedOrder.of(DAY, StrategyTicker.UPRO, OrderType.MOC, OrderDirection.BUY, 3,
                new BigDecimal("91.48"), OrderTiming.AT_CLOSE, "DCA_" + KEY);
    }

    static final BigDecimal REFERENCE = new BigDecimal("87.12"); // DCA 주문 기준가
    static final String KEY = "11111111-1111-1111-1111-111111111111";

    private TradingCandidatePlanner.CyclePlanCandidate candidate() {
        return new TradingCandidatePlanner.CyclePlanCandidate(
                new TradingCandidatePlanner.CycleState(ctx, null, null, null, null, null), List.of(planned), false, Map.of("DCA_" + KEY, REFERENCE));
    }

    @Test
    void open_candidate_passes_current_price_and_carries_reference_price() throws Exception {
        DcaRoundPlanner dcaRoundPlanner = mock(DcaRoundPlanner.class);
        TradingBalanceLoader balanceLoader = mock(TradingBalanceLoader.class);
        CycleOrderStrategies strategies = mock(CycleOrderStrategies.class);
        TradingCandidatePlanner withPlanner = new TradingCandidatePlanner(orderPort, null, orderPlanner, null, strategies, allocator,
                balanceLoader, publisher, new TradingBatchGuard(publisher), new AccountBudgetLock(), dcaRoundPlanner, recorder,
                new TradingParallelRunner(0));
        when(balanceLoader.loadBalanceOrThrow(ctx.strategy()))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(new AccountBalance(0, null, BigDecimal.ZERO), null));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), DAY)).thenReturn(List.of());
        BigDecimal premarket = new BigDecimal("95.00");
        when(dcaRoundPlanner.plan(eq(ctx), eq(DAY), any(), eq(List.of()), eq(premarket), any()))
                .thenReturn(List.of(new DcaDecision.Planned(planned, premarket)));

        List<TradingCandidatePlanner.CyclePlanCandidate> candidates = withPlanner.collectOpenCandidates(List.of(ctx),
                Map.of(StrategyTicker.UPRO, new PriceSnapshot(premarket, REFERENCE)), null, DAY);

        assertThat(candidates).singleElement().extracting(TradingCandidatePlanner.CyclePlanCandidate::dcaReferencePrices)
                .isEqualTo(Map.of("DCA_" + KEY, premarket));
    }

    @Test
    void approved_dca_order_is_saved_then_recorded_ordered_with_reference_price() throws Exception {
        when(allocator.allocate(any(), eq(DAY))).thenReturn(new TradingOrderBudgetAllocator.Allocation(
                List.of(new TradingOrderBudgetAllocator.Candidate(ctx, List.of(planned))), List.of(), List.of()));
        Order saved = new Order(UUID.randomUUID(), ctx.account().id(), ctx.currentCycle().id(), DAY, StrategyTicker.UPRO,
                OrderType.MOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA_" + KEY, BigDecimal.valueOf(3), new BigDecimal("91.48"),
                OrderStatus.PLANNED, null, null, null);
        // 저장 전 재확인은 빈 목록, 저장 후 조회는 저장된 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), DAY)).thenReturn(List.of(), List.of(saved));

        TradingCandidatePlanner.SaveAllocationResult result = planner.saveAllocatedOrders(List.of(candidate()), DAY);

        assertThat(result.savedContexts()).contains(ctx);
        verify(orderPlanner).savePlannedOrders(List.of(planned), ctx.account(), ctx.currentCycle().id());
        verify(recorder).recordOrdered(ctx, DAY, KEY, saved, REFERENCE);
    }

    @Test
    void rejected_dca_buy_records_insufficient_balance_without_balance_alert() throws Exception {
        when(orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), DAY)).thenReturn(List.of());
        when(allocator.allocate(any(), eq(DAY))).thenReturn(new TradingOrderBudgetAllocator.Allocation(
                List.of(), List.of(new TradingOrderBudgetAllocator.Candidate(ctx, List.of(planned))), List.of()));

        TradingCandidatePlanner.SaveAllocationResult result = planner.saveAllocatedOrders(List.of(candidate()), DAY);

        assertThat(result.savedContexts()).doesNotContain(ctx);
        verify(recorder).recordSkipped(eq(ctx), eq(DAY), eq(KEY), eq(OrderDirection.BUY), eq(DcaSkipReason.INSUFFICIENT_BALANCE), any(), eq(REFERENCE));
        verify(publisher, never()).publishEvent(any(InsufficientBalanceEvent.class));
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void rejected_dca_sell_records_no_holdings() throws Exception {
        when(orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), DAY)).thenReturn(List.of());
        when(allocator.allocate(any(), eq(DAY))).thenReturn(new TradingOrderBudgetAllocator.Allocation(
                List.of(), List.of(), List.of(new TradingOrderBudgetAllocator.Candidate(ctx, List.of(planned)))));

        planner.saveAllocatedOrders(List.of(candidate()), DAY);

        verify(recorder).recordSkipped(eq(ctx), eq(DAY), eq(KEY), any(), eq(DcaSkipReason.NO_HOLDINGS), any(), eq(REFERENCE));
        verify(publisher, never()).publishEvent(any(InsufficientBalanceEvent.class));
    }
}
