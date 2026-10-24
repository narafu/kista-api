package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.DcaCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.PrivacyCycleOrderStrategy;
import com.kista.tradingcore.matching.domain.strategy.VrCycleOrderStrategy;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.OrderPort;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.Order;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StrategyLedgerBudgetTest {

    static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    CyclePositionPort positionPort = mock(CyclePositionPort.class);
    OrderPort orderPort = mock(OrderPort.class);
    StrategyLedgerBudget budget = new StrategyLedgerBudget(positionPort, orderPort, new CycleOrderStrategies(List.of(
            new InfiniteCycleOrderStrategy(null, null), new PrivacyCycleOrderStrategy(null),
            new VrCycleOrderStrategy(null), new DcaCycleOrderStrategy())));

    private static PlannedOrder order(OrderDirection dir, OrderType type, int qty, String price, String leg) {
        return PlannedOrder.of(DAY, StrategyTicker.SOXL, type, dir, qty, new BigDecimal(price), OrderTiming.AT_CLOSE, leg);
    }

    private static PlannedOrder buy(int qty, String price, String leg) {
        return order(OrderDirection.BUY, OrderType.LOC, qty, price, leg);
    }

    private static StrategyCycle cycle(String startAmount) {
        return new StrategyCycle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(startAmount), null, DAY, null, null, null);
    }

    private static Order saved(StrategyCycle c, OrderDirection dir, OrderStatus status, int qty, String price) {
        return new Order(UUID.randomUUID(), UUID.randomUUID(), c.id(), DAY, StrategyTicker.SOXL, OrderType.LOC,
                OrderTiming.AT_CLOSE, dir, "L", BigDecimal.valueOf(qty), new BigDecimal(price), status, null, null, null);
    }

    @Test
    void buyWithinLedgerKeepsOrders() {
        List<PlannedOrder> orders = List.of(buy(3, "101.44", "PRIVACY_BUY_01"));
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.PRIVACY, orders, new BigDecimal("500"));
        assertThat(fit.orders()).isEqualTo(orders);
        assertThat(fit.trimmed()).isFalse();
        assertThat(fit.rejected()).isFalse();
    }

    @Test
    void privacyOverLedgerRejectsAllBuysKeepsSells() {
        PlannedOrder sell = order(OrderDirection.SELL, OrderType.LIMIT, 1, "110.00", "PRIVACY_SELL_01");
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.PRIVACY,
                List.of(buy(3, "101.44", "PRIVACY_BUY_01"), sell), new BigDecimal("177.61"));
        assertThat(fit.orders()).containsExactly(sell);
        assertThat(fit.requiredBuyUsd()).isEqualByComparingTo("304.32");
        assertThat(fit.rejected()).isTrue();
    }

    @Test
    void infiniteOverLedgerDropsCorrectionsFirst() {
        PlannedOrder base = buy(10, "20.00", "INFINITE_BUY_01");
        List<PlannedOrder> orders = List.of(base, buy(1, "9.00", "INFINITE_CORRECTION_01"),
                buy(1, "9.00", "INFINITE_CORRECTION_02"), buy(1, "9.00", "INFINITE_CORRECTION_03"));
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.INFINITE, orders, new BigDecimal("210"));
        assertThat(fit.orders()).extracting(PlannedOrder::orderLeg)
                .containsExactly("INFINITE_BUY_01", "INFINITE_CORRECTION_01");
        assertThat(fit.trimmed()).isTrue();
        assertThat(fit.rejected()).isFalse();
    }

    @Test
    void vrBootstrapOverLedgerRejected() {
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.VR,
                List.of(buy(5, "50.00", "VR_BOOTSTRAP_01")), new BigDecimal("100"));
        assertThat(fit.rejected()).isTrue();
        assertThat(fit.orders()).isEmpty();
    }

    @Test
    void dcaIgnoresLedger() {
        List<PlannedOrder> orders = List.of(buy(1, "100.00", "DCA_X"));
        StrategyLedgerBudget.LedgerFit fit = budget.fit(StrategyType.DCA, orders, BigDecimal.ZERO);
        assertThat(fit.orders()).isEqualTo(orders);
        assertThat(fit.rejected()).isFalse();
    }

    @Test
    void remainingFallsBackToStartAmountWhenNoPosition() {
        StrategyCycle c = cycle("1000");
        when(positionPort.findLatestOne(c.id())).thenReturn(Optional.empty());
        when(orderPort.findPlannedOrPlacedByCycleAndDate(c.id(), DAY)).thenReturn(List.of());
        assertThat(budget.remaining(c, DAY)).isEqualByComparingTo("1000");
    }

    @Test
    void remainingSubtractsSameDayCycleBuys() {
        StrategyCycle c = cycle("1000");
        when(positionPort.findLatestOne(c.id())).thenReturn(Optional.of(
                new CyclePosition(null, c.id(), new BigDecimal("1000"), null, null, BigDecimal.ZERO, null, null)));
        when(orderPort.findPlannedOrPlacedByCycleAndDate(c.id(), DAY)).thenReturn(List.of(
                saved(c, OrderDirection.BUY, OrderStatus.PLACED, 3, "100"),
                saved(c, OrderDirection.BUY, OrderStatus.SUBMITTING, 1, "100"),
                saved(c, OrderDirection.SELL, OrderStatus.PLACED, 1, "50")));
        assertThat(budget.remaining(c, DAY)).isEqualByComparingTo("600");
    }
}
