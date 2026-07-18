package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.BuyCompetitionPreview;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.StrategyCyclePort;
import com.kista.domain.port.out.StrategyPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.strategy.CycleOrderStrategies;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TradingBuyCompetitionSimulatorTest {

    @Mock StrategyPort strategyPort;
    @Mock StrategyCyclePort strategyCyclePort;
    @Mock OrderPort orderPort;
    @Mock BrokerAdapterRegistry registry;
    @Mock LiveBalancePort liveBalancePort;
    @Mock StrategyOrderPlanBuilder planBuilder;
    @Mock CycleOrderStrategies cycleOrderStrategies;
    @Mock CycleOrderStrategy vrOrderStrategy;
    @Mock CycleOrderStrategy infiniteOrderStrategy;

    TradingBuyCompetitionSimulator simulator;

    Account account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    LocalDate today = LocalDate.now();

    Strategy currentStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    StrategyCycle currentCycle = new StrategyCycle(UUID.randomUUID(), currentStrategy.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);

    @BeforeEach
    void setUp() {
        simulator = new TradingBuyCompetitionSimulator(strategyPort, strategyCyclePort, orderPort, registry, planBuilder, cycleOrderStrategies);
        lenient().doReturn(liveBalancePort).when(registry).require(any(Account.class), any());
        lenient().when(cycleOrderStrategies.of(Strategy.Type.INFINITE)).thenReturn(infiniteOrderStrategy);
        lenient().when(cycleOrderStrategies.of(Strategy.Type.VR)).thenReturn(vrOrderStrategy);
        lenient().when(infiniteOrderStrategy.allocationPriority()).thenReturn(1);
        lenient().when(vrOrderStrategy.allocationPriority()).thenReturn(0);
    }

    private Order buyOrder(Ticker ticker, int quantity, BigDecimal price) {
        return Order.planned(today, ticker, Order.OrderType.LOC, Order.OrderDirection.BUY, quantity, price);
    }

    @Test
    void simulate_sufficientBudget_whenNoCompetitors() {
        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00"))); // 200 USD

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.sufficientBudget()).isTrue();
        assertThat(result.availableDeposit()).isEqualByComparingTo("1000.00");
        assertThat(result.requiredForThisStrategy()).isEqualByComparingTo("200.00");
        assertThat(result.consumedByHigherPriority()).isEqualByComparingTo("0");
        assertThat(result.blockedByHigherPriority()).isEmpty();
        assertThat(result.uncertainStrategyIds()).isEmpty();
    }

    @Test
    void simulate_excludesCompetitor_thatAlreadyHasOrdersToday() {
        Strategy vrStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrategy.id(), UUID.randomUUID(),
                new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategy.id())).thenReturn(Optional.of(vrCycle));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(vrCycle.id(), today))
                .thenReturn(List.of(buyOrder(Ticker.TQQQ, 1, new BigDecimal("50.00"))));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00")));

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, new BigDecimal("50.00"));

        assertThat(result.blockedByHigherPriority()).isEmpty();
        assertThat(result.availableDeposit()).isEqualByComparingTo("950.00"); // 1000 - 50(otherStrategiesPlannedBuyUsd)
        verify(planBuilder, never()).build(eq(vrStrategy), any(), any(), any(), anyString());
    }

    @Test
    void simulate_blocksCurrentStrategy_whenHigherPriorityCompetitorConsumesBudget() {
        Strategy vrStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrategy.id(), UUID.randomUUID(),
                new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);
        CycleOrderStrategy.OrderPlan vrPlan = new CycleOrderStrategy.OrderPlan(
                null, List.of(buyOrder(Ticker.TQQQ, 10, new BigDecimal("90.00")))); // 900 USD

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategy.id())).thenReturn(Optional.of(vrCycle));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(vrCycle.id(), today)).thenReturn(List.of());
        when(planBuilder.build(eq(vrStrategy), eq(account), eq(vrCycle), eq(today), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(vrPlan, null));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00"))); // 200 USD

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.consumedByHigherPriority()).isEqualByComparingTo("900.00");
        assertThat(result.blockedByHigherPriority()).hasSize(1);
        assertThat(result.blockedByHigherPriority().get(0).strategyId()).isEqualTo(vrStrategy.id());
        assertThat(result.sufficientBudget()).isFalse(); // 900 + 200 > 1000
    }

    @Test
    void simulate_treatsFailedCompetitorAsZero_andRecordsUncertain() {
        Strategy vrStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrategy.id(), UUID.randomUUID(),
                new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategy.id())).thenReturn(Optional.of(vrCycle));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(vrCycle.id(), today)).thenReturn(List.of());
        when(planBuilder.build(eq(vrStrategy), eq(account), eq(vrCycle), eq(today), anyString()))
                .thenThrow(new IllegalStateException("가격 조회 실패"));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00")));

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.uncertainStrategyIds()).containsExactly(vrStrategy.id());
        assertThat(result.blockedByHigherPriority()).isEmpty();
        assertThat(result.sufficientBudget()).isTrue();
    }

    @Test
    void simulate_treatsSkippedCompetitorAsZero_andRecordsUncertain() {
        Strategy vrStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrategy.id(), UUID.randomUUID(),
                new BigDecimal("500.00"), null, LocalDate.now(), null, null, null);

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, vrStrategy));
        when(strategyCyclePort.findLatestByStrategyId(vrStrategy.id())).thenReturn(Optional.of(vrCycle));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(vrCycle.id(), today)).thenReturn(List.of());
        when(planBuilder.build(eq(vrStrategy), eq(account), eq(vrCycle), eq(today), anyString()))
                .thenReturn(new StrategyOrderPlanBuilder.PlanResult(null,
                        com.kista.domain.model.order.NextOrdersPreview.SkipReason.NO_CYCLE_HISTORY));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00")));

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.uncertainStrategyIds()).containsExactly(vrStrategy.id());
        assertThat(result.blockedByHigherPriority()).isEmpty();
        assertThat(result.sufficientBudget()).isTrue();
    }

    @Test
    void simulate_excludesPausedStrategy() {
        Strategy pausedVr = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.PAUSED, Ticker.TQQQ, Strategy.CycleSeedType.NONE);

        when(liveBalancePort.getLiveBalance(account, Ticker.SOXL))
                .thenReturn(new AccountBalance(0, null, new BigDecimal("1000.00")));
        when(strategyPort.findByAccountId(account.id())).thenReturn(List.of(currentStrategy, pausedVr));
        List<Order> buyOrders = List.of(buyOrder(Ticker.SOXL, 10, new BigDecimal("20.00")));

        BuyCompetitionPreview result = simulator.simulate(
                currentStrategy, account, currentCycle, buyOrders, today, BigDecimal.ZERO);

        assertThat(result.blockedByHigherPriority()).isEmpty();
        verifyNoInteractions(planBuilder);
        verify(strategyCyclePort, never()).findLatestByStrategyId(pausedVr.id());
    }
}
