package com.kista.application.service.trading;

import com.kista.application.service.broker.BrokerAdapterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.BatchContext;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.user.User;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.port.out.broker.LiveBalancePort;
import com.kista.domain.port.out.broker.SellableQuantityPort;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingOrderBudgetAllocatorTest {

    @Mock BrokerAdapterRegistry registry;
    @Mock LiveBalancePort liveBalancePort;
    @Mock SellableQuantityPort sellableQuantityPort;
    @Mock OrderPort orderPort;
    @Mock CycleOrderStrategy infiniteCycleOrderStrategy;
    @Mock CycleOrderStrategy privacyCycleOrderStrategy;
    @Mock CycleOrderStrategy vrCycleOrderStrategy;

    TradingOrderBudgetAllocator allocator;

    Account account;
    User user;
    LocalDate tradeDate;

    @BeforeEach
    void setUp() {
        account = DomainFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
        user = DomainFixtures.activeUserWithTelegram(account.userId());
        tradeDate = LocalDate.of(2026, 7, 15);
        when(infiniteCycleOrderStrategy.cycleType()).thenReturn(Strategy.Type.INFINITE);
        lenient().when(infiniteCycleOrderStrategy.allocationPriority()).thenReturn(1);
        when(privacyCycleOrderStrategy.cycleType()).thenReturn(Strategy.Type.PRIVACY);
        lenient().when(privacyCycleOrderStrategy.allocationPriority()).thenReturn(2);
        when(vrCycleOrderStrategy.cycleType()).thenReturn(Strategy.Type.VR);
        lenient().when(vrCycleOrderStrategy.allocationPriority()).thenReturn(0);
        CycleOrderStrategies cycleOrderStrategies = new CycleOrderStrategies(List.of(
                infiniteCycleOrderStrategy, privacyCycleOrderStrategy, vrCycleOrderStrategy));
        allocator = new TradingOrderBudgetAllocator(registry, orderPort, cycleOrderStrategies);
        lenient().when(registry.require(any(Account.class), eq(LiveBalancePort.class))).thenReturn(liveBalancePort);
        lenient().when(registry.require(any(Account.class), eq(SellableQuantityPort.class))).thenReturn(sellableQuantityPort);
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(eq(account.id()), eq(tradeDate))).thenReturn(BigDecimal.ZERO);
        lenient().when(sellableQuantityPort.getSellableQuantity(any(), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 100));
    }

    @Test
    void allocate_prioritizesVrThenInfiniteThenPrivacyWithLimitedCash() {
        when(liveBalancePort.getLiveBalance(eq(account), eq(Strategy.Ticker.SOXL)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("3000.00")));

        TradingOrderBudgetAllocator.Candidate vr = candidate(Strategy.Type.VR, "1500.00");
        TradingOrderBudgetAllocator.Candidate infinite = candidate(Strategy.Type.INFINITE, "1000.00");
        TradingOrderBudgetAllocator.Candidate privacy = candidate(Strategy.Type.PRIVACY, "1000.00");

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(privacy, infinite, vr), tradeDate);

        assertThat(result.approved()).containsExactly(vr, infinite);
        assertThat(result.rejectedBuy()).containsExactly(privacy);
    }

    @Test
    void allocate_sameStrategyTypeApprovesSmallerBuyTotalFirst() {
        when(liveBalancePort.getLiveBalance(eq(account), eq(Strategy.Ticker.SOXL)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("1000.00")));

        TradingOrderBudgetAllocator.Candidate large = candidate(Strategy.Type.INFINITE, "1200.00");
        TradingOrderBudgetAllocator.Candidate small = candidate(Strategy.Type.INFINITE, "800.00");

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(large, small), tradeDate);

        assertThat(result.approved()).containsExactly(small);
        assertThat(result.rejectedBuy()).containsExactly(large);
    }

    @Test
    void allocate_prioritizesVrThenInfiniteThenPrivacyForLimitedSellableQuantity() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 3));

        TradingOrderBudgetAllocator.Candidate vr = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                Strategy.Type.VR, sell("25.00", 2));
        TradingOrderBudgetAllocator.Candidate infinite = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Strategy.Type.INFINITE, sell("25.00", 2));
        TradingOrderBudgetAllocator.Candidate privacy = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Strategy.Type.PRIVACY, sell("25.00", 2));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(privacy, infinite, vr), tradeDate);

        assertThat(result.approved()).containsExactly(vr);
        assertThat(result.rejectedSell()).containsExactly(infinite, privacy);
    }

    @Test
    void allocate_sameStrategyTypeApprovesSmallerSellQuantityFirst() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 4));

        TradingOrderBudgetAllocator.Candidate large = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Strategy.Type.INFINITE, sell("25.00", 4));
        TradingOrderBudgetAllocator.Candidate small = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Strategy.Type.INFINITE, sell("25.00", 2));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(large, small), tradeDate);

        assertThat(result.approved()).containsExactly(small);
        assertThat(result.rejectedSell()).containsExactly(large);
    }

    @Test
    void allocate_sameStrategyTypeAndSellQuantityApprovesLowerStrategyUuidFirst() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 2));

        TradingOrderBudgetAllocator.Candidate lowerStrategyId = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Strategy.Type.INFINITE, sell("25.00", 2));
        TradingOrderBudgetAllocator.Candidate higherStrategyId = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Strategy.Type.INFINITE, sell("25.00", 2));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(
                List.of(higherStrategyId, lowerStrategyId), tradeDate);

        assertThat(result.approved()).containsExactly(lowerStrategyId);
        assertThat(result.rejectedSell()).containsExactly(higherStrategyId);
    }

    @Test
    void allocate_sameStrategyTypeAndSellQuantityAndStrategyUuidUsesLowerCycleUuid() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 2));

        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TradingOrderBudgetAllocator.Candidate lowerCycleId = candidate(
                strategyId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Strategy.Type.INFINITE, sell("25.00", 2));
        TradingOrderBudgetAllocator.Candidate higherCycleId = candidate(
                strategyId,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Strategy.Type.INFINITE, sell("25.00", 2));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(
                List.of(higherCycleId, lowerCycleId), tradeDate);

        assertThat(result.approved()).containsExactly(lowerCycleId);
        assertThat(result.rejectedSell()).containsExactly(higherCycleId);
    }

    @Test
    void allocate_buyIsAllOrNothingWithinCycle() {
        when(liveBalancePort.getLiveBalance(eq(account), eq(Strategy.Ticker.SOXL)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("1000.00")));

        TradingOrderBudgetAllocator.Candidate candidate = candidate(Strategy.Type.INFINITE,
                buy("700.00"), buy("400.00"));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).isEmpty();
        assertThat(result.rejectedBuy()).containsExactly(candidate);
    }

    @Test
    void allocate_sellDoesNotConsumeBuyBudget() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 10));

        TradingOrderBudgetAllocator.Candidate sellOnly = candidate(Strategy.Type.PRIVACY,
                sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(sellOnly), tradeDate);

        assertThat(result.approved()).containsExactly(sellOnly);
        assertThat(result.rejectedBuy()).isEmpty();
        assertThat(result.rejectedSell()).isEmpty();
    }

    @Test
    void allocate_rejectsSellWhenSellableQuantityIsInsufficient() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 2));

        TradingOrderBudgetAllocator.Candidate tooMuchSell = candidate(Strategy.Type.VR,
                sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(tooMuchSell), tradeDate);

        assertThat(result.approved()).isEmpty();
        assertThat(result.rejectedSell()).containsExactly(tooMuchSell);
    }

    @Test
    void allocate_rejectsSellWhenExistingReservationsLeaveInsufficientQuantity() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 5));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                account.id(), tradeDate, Strategy.Ticker.SOXL)).thenReturn(3);

        TradingOrderBudgetAllocator.Candidate candidate = candidate(Strategy.Type.VR, sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).isEmpty();
        assertThat(result.rejectedSell()).containsExactly(candidate);
    }

    @Test
    void allocate_rejectsLaterSellsWhenAccountTickerTotalExceedsSellableQuantity() {
        when(sellableQuantityPort.getSellableQuantity(eq(Strategy.Ticker.SOXL), eq(account)))
                .thenReturn(new SellableQuantity("SOXL", 5));

        TradingOrderBudgetAllocator.Candidate first = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Strategy.Type.PRIVACY, sell("25.00", 3));
        TradingOrderBudgetAllocator.Candidate second = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Strategy.Type.INFINITE, sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(second, first), tradeDate);

        assertThat(result.approved()).containsExactly(second);
        assertThat(result.rejectedSell()).containsExactly(first);
    }

    @Test
    void allocate_preservesOriginalOrderSequenceWhenBothDirectionsAreApproved() {
        when(liveBalancePort.getLiveBalance(eq(account), eq(Strategy.Ticker.SOXL)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("1000.00")));

        Order firstBuy = buy("100.00");
        Order sell = sell("25.00", 3);
        Order secondBuy = buy("200.00");
        TradingOrderBudgetAllocator.Candidate candidate = candidate(
                Strategy.Type.INFINITE, firstBuy, sell, secondBuy);

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).singleElement()
                .satisfies(approved -> assertThat(approved.orders())
                        .containsExactly(firstBuy, sell, secondBuy));
    }

    @Test
    void allocate_keepsOnlyApprovedDirectionInOriginalOrderForPartialApproval() {
        when(liveBalancePort.getLiveBalance(eq(account), eq(Strategy.Ticker.SOXL)))
                .thenReturn(new AccountBalance(100, new BigDecimal("20.00"), new BigDecimal("150.00")));

        Order firstBuy = buy("100.00");
        Order firstSell = sell("25.00", 3);
        Order secondBuy = buy("100.00");
        Order secondSell = sell("26.00", 2);
        TradingOrderBudgetAllocator.Candidate candidate = candidate(
                Strategy.Type.INFINITE, firstBuy, firstSell, secondBuy, secondSell);

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).singleElement()
                .satisfies(approved -> assertThat(approved.orders())
                        .containsExactly(firstSell, secondSell));
        assertThat(result.rejectedBuy()).singleElement()
                .satisfies(rejected -> assertThat(rejected.orders())
                        .containsExactly(firstBuy, secondBuy));
    }

    private TradingOrderBudgetAllocator.Candidate candidate(Strategy.Type type, String buyAmount) {
        return candidate(type, buy(buyAmount));
    }

    private TradingOrderBudgetAllocator.Candidate candidate(Strategy.Type type, Order... orders) {
        return candidate(UUID.randomUUID(), UUID.randomUUID(), type, orders);
    }

    private TradingOrderBudgetAllocator.Candidate candidate(UUID strategyId, UUID cycleId, Strategy.Type type,
                                                             Order... orders) {
        Strategy strategy = new Strategy(strategyId, account.id(), type,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, tradeDate, null, null, null);
        return new TradingOrderBudgetAllocator.Candidate(
                new BatchContext(strategy, cycle, account, user),
                List.of(orders));
    }

    private Order buy(String amount) {
        return new Order(null, null, null, tradeDate, Strategy.Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal(amount),
                Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order sell(String price, int quantity) {
        return new Order(null, null, null, tradeDate, Strategy.Ticker.SOXL, Order.OrderType.LIMIT,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL, quantity, new BigDecimal(price),
                Order.OrderStatus.PLANNED, null, null, null);
    }
}
