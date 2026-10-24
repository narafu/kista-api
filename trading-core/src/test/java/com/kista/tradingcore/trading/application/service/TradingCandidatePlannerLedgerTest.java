package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.event.InsufficientBalanceEvent;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.application.service.support.TradingBatchGuard;
import com.kista.tradingcore.trading.application.service.support.TradingOrderPlanner;
import com.kista.tradingcore.trading.application.service.support.TradingParallelRunner;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.TradingAccount;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 같은 날 재실행 시 이미 저장된 슬롯은 원장 잔여에서 한 번만 차감된다(신규 후보가 같은 슬롯을 다시 세지 않는다)
class TradingCandidatePlannerLedgerTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    @Test
    void alreadySavedSlotIsSubtractedOnceNotTwice() throws Exception {
        OrderPort orderPort = mock(OrderPort.class);
        CyclePositionPort positionPort = mock(CyclePositionPort.class);
        TradingOrderPlanner orderPlanner = mock(TradingOrderPlanner.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        CycleOrderStrategies strategies = new CycleOrderStrategies(List.of(new InfiniteCycleOrderStrategy(null, null)));
        TradingCandidatePlanner planner = new TradingCandidatePlanner(orderPort, null, orderPlanner, null, strategies,
                new StrategyLedgerBudget(positionPort, orderPort, strategies), null, publisher,
                new TradingBatchGuard(publisher), new AccountBudgetLock(), null, null, new TradingParallelRunner(0));

        TradingAccount account = TradingFixtures.tradingAccount(UUID.randomUUID(), UUID.randomUUID());
        Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
                new BigDecimal("100"), null, DAY.minusDays(1), null, null, null);
        BatchContext ctx = new BatchContext(strategy, cycle, account, TradingFixtures.tradingUserProfile(account.userId()));

        // 원장 100: 슬롯 A(60)는 이미 저장됨, 신규 후보는 A(중복)+B(30) — A를 다시 세면 40 < 90으로 B까지 거절된다
        PlannedOrder slotA = PlannedOrder.of(DAY, StrategyTicker.SOXL, OrderType.LOC, OrderDirection.BUY, 3,
                new BigDecimal("20"), OrderTiming.AT_CLOSE, "INFINITE_BUY_01");
        PlannedOrder slotB = PlannedOrder.of(DAY, StrategyTicker.SOXL, OrderType.LOC, OrderDirection.BUY, 1,
                new BigDecimal("30"), OrderTiming.AT_CLOSE, "INFINITE_BUY_02");
        Order savedA = new Order(UUID.randomUUID(), account.id(), cycle.id(), DAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, OrderDirection.BUY, "INFINITE_BUY_01", BigDecimal.valueOf(3), new BigDecimal("20"),
                OrderStatus.PLANNED, null, null, null);
        when(positionPort.findLatestOne(cycle.id())).thenReturn(Optional.of(
                new CyclePosition(null, cycle.id(), new BigDecimal("100"), null, null, BigDecimal.ZERO, null, null)));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(cycle.id(), DAY)).thenReturn(List.of(savedA));

        planner.saveAllocatedOrders(List.of(new TradingCandidatePlanner.CyclePlanCandidate(
                new TradingCandidatePlanner.CycleState(ctx, null, null, null, null, null), List.of(slotA, slotB), true)), DAY);

        verify(orderPlanner).savePlannedOrders(List.of(slotB), account, cycle.id());
        verify(publisher, never()).publishEvent(any(InsufficientBalanceEvent.class));
    }
}
