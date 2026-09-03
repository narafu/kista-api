package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.trading.domain.model.NextOrdersPreview.SkipReason;
import com.kista.trading.domain.model.AccountBalance;
import com.kista.domain.model.strategy.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.PrivacyTradePort;
import com.kista.broker.domain.port.out.BrokerPricePort;
import com.kista.trading.domain.strategy.CycleOrderStrategies;
import com.kista.trading.domain.strategy.CycleOrderStrategy;
import com.kista.trading.domain.strategy.VrCycleOrderStrategy;
import com.kista.trading.domain.strategy.VrStrategy;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyOrderPlanBuilderTest {

    @Mock TradingBalanceLoader balanceLoader;
    @Mock BrokerAdapterRegistry registry;
    @Mock BrokerPricePort pricePort;
    @Mock PrivacyTradePort privacyTradePort;
    @Mock CycleOrderComputer orderComputer;
    @Mock CycleOrderStrategies cycleOrderStrategies;
    @Mock CycleOrderStrategy orderStrategy;

    StrategyOrderPlanBuilder builder;

    Account account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.INFINITE,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.NONE);
    StrategyCycle cycle = new StrategyCycle(UUID.randomUUID(), strategy.id(), UUID.randomUUID(),
            new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null);
    LocalDate today = LocalDate.now();

    @BeforeEach
    void setUp() {
        builder = new StrategyOrderPlanBuilder(balanceLoader, registry, privacyTradePort, orderComputer, cycleOrderStrategies);
        lenient().when(cycleOrderStrategies.of(strategy)).thenReturn(orderStrategy);
        lenient().doReturn(pricePort).when(registry).require(any(Account.class), any());
    }

    @Test
    void build_returnsSkip_whenBalanceLoadIsSkip() {
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(null, SkipReason.NO_CYCLE_HISTORY));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label");

        assertThat(result.isSkip()).isTrue();
        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_CYCLE_HISTORY);
        verifyNoInteractions(orderComputer);
    }

    @Test
    void build_fetchesPrevClose_whenStrategyRequiresIt() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(orderStrategy.requiresPrevClose()).thenReturn(true);
        when(pricePort.getPrevClose(Ticker.SOXL, account)).thenReturn(new BigDecimal("21.00"));
        when(privacyTradePort.findBaseIfPrivacy(strategy, today)).thenReturn(null);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of());
        when(orderComputer.compute(balance, strategy, new BigDecimal("21.00"), today, cycle, null, "label", null))
                .thenReturn(Optional.of(plan));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label");

        assertThat(result.isSkip()).isFalse();
        assertThat(result.plan()).isSameAs(plan);
    }

    @Test
    void build_fetchesPrevClose_forVrPreviewReferencePrice() {
        Strategy vrStrategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.VR,
                Strategy.Status.ACTIVE, Ticker.TQQQ, Strategy.CycleSeedType.NONE);
        StrategyCycle vrCycle = new StrategyCycle(UUID.randomUUID(), vrStrategy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, today, null, null, null);
        AccountBalance balance = new AccountBalance(0, BigDecimal.ZERO, new BigDecimal("1000.00"));
        CycleOrderStrategy vrOrderStrategy = new VrCycleOrderStrategy(new VrStrategy());

        when(cycleOrderStrategies.of(vrStrategy)).thenReturn(vrOrderStrategy);
        when(balanceLoader.tryLoadBalance(vrStrategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(pricePort.getPrevClose(Ticker.TQQQ, account)).thenReturn(new BigDecimal("100.00"));
        when(privacyTradePort.findBaseIfPrivacy(vrStrategy, today)).thenReturn(null);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of());
        when(orderComputer.compute(eq(balance), eq(vrStrategy), nullable(BigDecimal.class),
                eq(today), eq(vrCycle), eq(null), eq("vr-preview"), eq(null)))
                .thenReturn(Optional.of(plan));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(vrStrategy, account, vrCycle, today, "vr-preview");

        assertThat(result.isSkip()).isFalse();
        assertThat(result.plan()).isSameAs(plan);
        verify(pricePort).getPrevClose(Ticker.TQQQ, account);
        verify(orderComputer).compute(balance, vrStrategy, new BigDecimal("100.00"),
                today, vrCycle, null, "vr-preview", null);
    }

    @Test
    void build_skipsPrevCloseFetch_whenStrategyDoesNotRequireIt() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(orderStrategy.requiresPrevClose()).thenReturn(false);
        when(privacyTradePort.findBaseIfPrivacy(strategy, today)).thenReturn(null);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of());
        when(orderComputer.compute(balance, strategy, null, today, cycle, null, "label", null))
                .thenReturn(Optional.of(plan));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label");

        assertThat(result.isSkip()).isFalse();
        verify(pricePort, never()).getPrevClose(any(), any());
    }

    @Test
    void build_usesPrevCloseCache_insteadOfLiveCall_whenTickerPresent() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(orderStrategy.requiresPrevClose()).thenReturn(true);
        when(privacyTradePort.findBaseIfPrivacy(strategy, today)).thenReturn(null);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of());
        when(orderComputer.compute(balance, strategy, new BigDecimal("22.00"), today, cycle, null, "label", null))
                .thenReturn(Optional.of(plan));
        Map<Ticker, BigDecimal> prevCloseCache = Map.of(Ticker.SOXL, new BigDecimal("22.00"));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label", prevCloseCache);

        assertThat(result.isSkip()).isFalse();
        assertThat(result.plan()).isSameAs(plan);
        verify(pricePort, never()).getPrevClose(any(), any());
    }

    @Test
    void build_fallsBackToLiveCall_whenTickerMissingFromPrevCloseCache() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(orderStrategy.requiresPrevClose()).thenReturn(true);
        when(pricePort.getPrevClose(Ticker.SOXL, account)).thenReturn(new BigDecimal("21.00"));
        when(privacyTradePort.findBaseIfPrivacy(strategy, today)).thenReturn(null);
        CycleOrderStrategy.OrderPlan plan = new CycleOrderStrategy.OrderPlan(null, null, List.of());
        when(orderComputer.compute(balance, strategy, new BigDecimal("21.00"), today, cycle, null, "label", null))
                .thenReturn(Optional.of(plan));

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label", Map.of());

        assertThat(result.isSkip()).isFalse();
        verify(pricePort).getPrevClose(Ticker.SOXL, account);
    }

    @Test
    void build_returnsSkipNoPrivacyBase_whenComputeReturnsEmpty() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        when(balanceLoader.tryLoadBalance(strategy))
                .thenReturn(new TradingBalanceLoader.BalanceLoad(balance, null));
        when(orderStrategy.requiresPrevClose()).thenReturn(false);
        when(privacyTradePort.findBaseIfPrivacy(strategy, today)).thenReturn(null);
        when(orderComputer.compute(balance, strategy, null, today, cycle, null, "label", null))
                .thenReturn(Optional.empty());

        StrategyOrderPlanBuilder.PlanResult result = builder.build(strategy, account, cycle, today, "label");

        assertThat(result.isSkip()).isTrue();
        assertThat(result.skipReason()).isEqualTo(SkipReason.NO_PRIVACY_BASE);
    }
}
