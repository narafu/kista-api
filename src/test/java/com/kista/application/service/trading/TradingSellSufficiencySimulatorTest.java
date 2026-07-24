package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.SellSufficiencyPreview;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
import com.kista.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingSellSufficiencySimulatorTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock SellableQuantityPort sellableQuantityPort;
    @Mock OrderPort orderPort;

    TradingSellSufficiencySimulator simulator;

    Account account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
    LocalDate today = LocalDate.now();
    Strategy strategy = new Strategy(UUID.randomUUID(), account.id(), Strategy.Type.PRIVACY,
            Strategy.Status.ACTIVE, Ticker.SOXL, Strategy.CycleSeedType.MAX);

    @BeforeEach
    void setUp() {
        simulator = new TradingSellSufficiencySimulator(registry, orderPort);
        lenient().when(registry.require(any(Account.class), eq(SellableQuantityPort.class))).thenReturn(sellableQuantityPort);
    }

    private Order sellOrder(int quantity, BigDecimal price) {
        return Order.planned(today, Ticker.SOXL, Order.OrderType.LIMIT, Order.OrderDirection.SELL, quantity, price);
    }

    @Test
    void simulate_sufficient_whenSellableQuantityCoversRequiredAndReserved() {
        when(sellableQuantityPort.getSellableQuantity(Ticker.SOXL, account))
                .thenReturn(new SellableQuantity("SOXL", 10));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(account.id(), today, Ticker.SOXL))
                .thenReturn(2);
        List<Order> sellOrders = List.of(sellOrder(5, new BigDecimal("25.00")));

        SellSufficiencyPreview result = simulator.simulate(strategy, account, sellOrders, today);

        assertThat(result.sufficientQuantity()).isTrue();
        assertThat(result.sellableQuantity()).isEqualTo(10);
        assertThat(result.reservedQuantity()).isEqualTo(2);
        assertThat(result.requiredQuantity()).isEqualTo(5);
        assertThat(result.liveQuantityUnavailable()).isFalse();
    }

    @Test
    void simulate_insufficient_whenRequiredAloneExceedsSellableQuantity() {
        when(sellableQuantityPort.getSellableQuantity(Ticker.SOXL, account))
                .thenReturn(new SellableQuantity("SOXL", 2));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(account.id(), today, Ticker.SOXL))
                .thenReturn(0);
        List<Order> sellOrders = List.of(sellOrder(3, new BigDecimal("25.00")));

        SellSufficiencyPreview result = simulator.simulate(strategy, account, sellOrders, today);

        assertThat(result.sufficientQuantity()).isFalse();
        assertThat(result.sellableQuantity()).isEqualTo(2);
        assertThat(result.requiredQuantity()).isEqualTo(3);
    }

    @Test
    void simulate_insufficient_whenExistingReservationsLeaveNotEnoughQuantity() {
        when(sellableQuantityPort.getSellableQuantity(Ticker.SOXL, account))
                .thenReturn(new SellableQuantity("SOXL", 5));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(account.id(), today, Ticker.SOXL))
                .thenReturn(3);
        List<Order> sellOrders = List.of(sellOrder(3, new BigDecimal("25.00")));

        SellSufficiencyPreview result = simulator.simulate(strategy, account, sellOrders, today);

        assertThat(result.sufficientQuantity()).isFalse();
        assertThat(result.reservedQuantity()).isEqualTo(3);
    }

    @Test
    void simulate_sumsMultipleSellOrderQuantities_asRequiredQuantity() {
        when(sellableQuantityPort.getSellableQuantity(Ticker.SOXL, account))
                .thenReturn(new SellableQuantity("SOXL", 20));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(account.id(), today, Ticker.SOXL))
                .thenReturn(0);
        List<Order> sellOrders = List.of(
                sellOrder(4, new BigDecimal("25.00")),
                sellOrder(6, new BigDecimal("26.00")));

        SellSufficiencyPreview result = simulator.simulate(strategy, account, sellOrders, today);

        assertThat(result.requiredQuantity()).isEqualTo(10);
        assertThat(result.sufficientQuantity()).isTrue();
    }

    @Test
    void simulate_returnsUnavailable_whenBrokerQuantityLookupFails() {
        when(sellableQuantityPort.getSellableQuantity(Ticker.SOXL, account))
                .thenThrow(new com.kista.domain.model.toss.TossApiException("Toss API 토큰 재시도 실패: 401", null));
        List<Order> sellOrders = List.of(sellOrder(3, new BigDecimal("25.00")));

        SellSufficiencyPreview result = simulator.simulate(strategy, account, sellOrders, today);

        assertThat(result.liveQuantityUnavailable()).isTrue();
        assertThat(result.sufficientQuantity()).isTrue();
        assertThat(result.sellableQuantity()).isEqualTo(0);
        assertThat(result.requiredQuantity()).isEqualTo(3);
    }
}
