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
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.Market;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
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

import static com.kista.tradingcore.support.TradingFixtures.stockDcaRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// DCA 후보의 저장 후 ORDERED 이력 기록 — 원장 상한과 무관하게 저장
class TradingCandidatePlannerDcaTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    OrderPort orderPort = mock(OrderPort.class);
    TradingOrderPlanner orderPlanner = mock(TradingOrderPlanner.class);
    // DCA는 원장을 쓰지 않으므로(occupiesSeed=false) 실제 원장 상한을 연결해도 거절되지 않는다 — 원장 0, 포지션 없음
    StrategyLedgerBudget ledgerBudget;
    ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    DcaExecutionRecorder recorder = mock(DcaExecutionRecorder.class);
    TradingCandidatePlanner planner;
    BatchContext ctx;
    PlannedOrder planned;

    @BeforeEach
    void setUp() {
        ledgerBudget = new StrategyLedgerBudget(mock(CyclePositionPort.class), orderPort, STRATEGIES);
        planner = new TradingCandidatePlanner(orderPort, null, orderPlanner, null, STRATEGIES, ledgerBudget, null,
                publisher, new TradingBatchGuard(publisher), new AccountBudgetLock(), mock(DcaPlanInputCollector.class), recorder,
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

    static final CycleOrderStrategies STRATEGIES = new CycleOrderStrategies(List.of(new DcaCycleOrderStrategy()));
    static final String KEY2 = "22222222-2222-2222-2222-222222222222";
    static final BigDecimal PREMARKET = new BigDecimal("95.00");
    static final AccountBalance LEDGER = new AccountBalance(10, new BigDecimal("80.00"), BigDecimal.ZERO); // 원장 10주 평단 80

    DcaPlanInputCollector collector = mock(DcaPlanInputCollector.class);
    TradingBalanceLoader balanceLoader = mock(TradingBalanceLoader.class);

    // 실제 CycleOrderComputer·DCA 커널로 plan() 정규 경로를 태우고 수집기만 스텁한다
    private TradingCandidatePlanner wiredPlanner() {
        CycleOrderComputer computer = new CycleOrderComputer(STRATEGIES, null, null, null, null, null, orderPort);
        return new TradingCandidatePlanner(orderPort, computer, orderPlanner, null, STRATEGIES, ledgerBudget,
                balanceLoader, publisher, new TradingBatchGuard(publisher), new AccountBudgetLock(), collector, recorder,
                new TradingParallelRunner(0));
    }

    private void stubCollected(Map<String, Order> recoveries, DcaRule... rules) {
        when(balanceLoader.loadBalanceOrThrow(ctx.strategy())).thenReturn(new TradingBalanceLoader.BalanceLoad(LEDGER, null));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), DAY)).thenReturn(List.of());
        List<DcaCycleOrderStrategy.DcaRuleInputs> due = java.util.Arrays.stream(rules)
                .map(r -> new DcaCycleOrderStrategy.DcaRuleInputs(r, Market.US_STOCK, List.of(REFERENCE), LEDGER,
                        r.direction() == OrderDirection.SELL ? BigDecimal.TEN : null, null, PREMARKET, null, null))
                .toList();
        when(collector.collect(eq(ctx), eq(DAY), any(), eq(List.of()), eq(PREMARKET), eq(LEDGER)))
                .thenReturn(new DcaCollected(new CycleOrderStrategy.PlanContext.DcaPlanInputs(due), recoveries));
    }

    private List<TradingCandidatePlanner.CyclePlanCandidate> collectOpen() throws Exception {
        return wiredPlanner().collectOpenCandidates(List.of(ctx), Map.of(StrategyTicker.UPRO, new PriceSnapshot(PREMARKET, REFERENCE)), null, DAY);
    }

    @Test
    void DCA_같은_MOO에_BUY와_SELL_규칙이_모두_주문된다() throws Exception {
        stubCollected(Map.of(), stockDcaRule(KEY, OrderDirection.BUY, "300", OrderType.MOO),
                stockDcaRule(KEY2, OrderDirection.SELL, "300", OrderType.MOO));

        List<TradingCandidatePlanner.CyclePlanCandidate> candidates = collectOpen();

        TradingCandidatePlanner.CyclePlanCandidate candidate = candidates.getFirst();
        // BUY 기준가 = max(전일 87.12, 현재 95) = 95 → 300/95 = 3주, SELL 기준가 = 전일 종가 87.12 → 3주
        assertThat(candidate.creatableOrders()).extracting(PlannedOrder::orderLeg, PlannedOrder::direction, PlannedOrder::quantity)
                .containsExactly(tuple("DCA_" + KEY, OrderDirection.BUY, 3), tuple("DCA_" + KEY2, OrderDirection.SELL, 3));
        assertThat(candidate.dcaReferencePrices()).isEqualTo(Map.of("DCA_" + KEY, PREMARKET, "DCA_" + KEY2, REFERENCE));
        verifyNoInteractions(recorder);
    }

    @Test
    void DCA_조건미충족_규칙은_skip_이력_나머지는_주문() throws Exception {
        // 평단 80 × 0.95 = 76 < 기준가 → AVG_PRICE ≤ −5% 미충족
        stubCollected(Map.of(), stockDcaRule(KEY, OrderDirection.BUY, "300", OrderType.MOO,
                        new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5"))),
                stockDcaRule(KEY2, OrderDirection.SELL, "300", OrderType.MOO));

        List<TradingCandidatePlanner.CyclePlanCandidate> candidates = collectOpen();

        assertThat(candidates.getFirst().creatableOrders()).extracting(PlannedOrder::orderLeg).containsExactly("DCA_" + KEY2);
        verify(recorder).recordSkipped(eq(ctx), eq(DAY), eq(KEY), eq(OrderDirection.BUY), eq(DcaSkipReason.CONDITION_NOT_MET), anyString(), any());
        verify(recorder, never()).recordSkipped(any(), any(), eq(KEY2), any(), any(), any(), any());
    }

    @Test
    void DCA_수집기가_돌려준_복구_대상은_ORDERED로_기록한다() throws Exception {
        Order existing = new Order(UUID.randomUUID(), ctx.account().id(), ctx.currentCycle().id(), DAY, StrategyTicker.UPRO,
                OrderType.MOO, OrderTiming.AT_OPEN, OrderDirection.BUY, "DCA_" + KEY, BigDecimal.valueOf(3), new BigDecimal("91.48"),
                OrderStatus.FILLED, "E1", BigDecimal.valueOf(3), new BigDecimal("88.00"));
        stubCollected(Map.of(KEY, existing));

        List<TradingCandidatePlanner.CyclePlanCandidate> candidates = collectOpen();

        verify(recorder).recordOrdered(ctx, DAY, KEY, existing, null);
        assertThat(candidates).allSatisfy(c -> assertThat(c.creatableOrders()).isEmpty());
    }

    @Test
    void dca_buy_is_saved_without_ledger_then_recorded_ordered_with_reference_price() throws Exception {
        Order saved = new Order(UUID.randomUUID(), ctx.account().id(), ctx.currentCycle().id(), DAY, StrategyTicker.UPRO,
                OrderType.MOC, OrderTiming.AT_CLOSE, OrderDirection.BUY, "DCA_" + KEY, BigDecimal.valueOf(3), new BigDecimal("91.48"),
                OrderStatus.PLANNED, null, null, null);
        // 저장 전 재확인은 빈 목록, 이후 조회(원장 잔여·ORDERED 기록)는 저장된 주문
        when(orderPort.findPlannedOrPlacedByCycleAndDate(ctx.currentCycle().id(), DAY)).thenReturn(List.of(), List.of(saved));

        TradingCandidatePlanner.SaveAllocationResult result = planner.saveAllocatedOrders(List.of(candidate()), DAY);

        assertThat(result.savedContexts()).contains(ctx);
        verify(orderPlanner).savePlannedOrders(List.of(planned), ctx.account(), ctx.currentCycle().id());
        verify(recorder).recordOrdered(ctx, DAY, KEY, saved, REFERENCE);
        verify(publisher, never()).publishEvent(any(InsufficientBalanceEvent.class));
    }
}
