package com.kista.trading.application.service;

import com.kista.broker.application.service.BrokerAdapterRegistry;
import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.SellableQuantity;
import com.kista.account.domain.model.Account;
import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.BatchContext;
import com.kista.trading.domain.model.StrategyRef;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.user.domain.model.User;
import com.kista.trading.application.port.output.OrderPort;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.broker.application.port.output.LiveBalancePort;
import com.kista.broker.application.port.output.SellableQuantityPort;
import com.kista.trading.domain.strategy.CycleOrderStrategies;
import com.kista.trading.domain.strategy.CycleOrderStrategy;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

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
        when(infiniteCycleOrderStrategy.cycleType()).thenReturn(StrategyType.INFINITE);
        lenient().when(infiniteCycleOrderStrategy.allocationPriority()).thenReturn(1);
        when(privacyCycleOrderStrategy.cycleType()).thenReturn(StrategyType.PRIVACY);
        lenient().when(privacyCycleOrderStrategy.allocationPriority()).thenReturn(2);
        when(vrCycleOrderStrategy.cycleType()).thenReturn(StrategyType.VR);
        lenient().when(vrCycleOrderStrategy.allocationPriority()).thenReturn(0);
        CycleOrderStrategies cycleOrderStrategies = new CycleOrderStrategies(List.of(
                infiniteCycleOrderStrategy, privacyCycleOrderStrategy, vrCycleOrderStrategy));
        allocator = new TradingOrderBudgetAllocator(registry, orderPort, cycleOrderStrategies, new TradingParallelRunner(2));
        lenient().when(registry.require(any(BrokerAccountRef.class), eq(LiveBalancePort.class))).thenReturn(liveBalancePort);
        lenient().when(registry.require(any(BrokerAccountRef.class), eq(SellableQuantityPort.class))).thenReturn(sellableQuantityPort);
        lenient().when(orderPort.sumPlannedBuyByAccountAndDate(eq(account.id()), eq(tradeDate))).thenReturn(BigDecimal.ZERO);
        lenient().when(sellableQuantityPort.getSellableQuantity(any(), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 100));
    }

    @Test
    void allocate_prioritizesVrThenInfiniteThenPrivacyWithLimitedCash() {
        when(liveBalancePort.getLiveBalance(eq(account.toBrokerRef()), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(100, new BigDecimal("20.00"), new BigDecimal("3000.00")));

        TradingOrderBudgetAllocator.Candidate vr = candidate(StrategyType.VR, "1500.00");
        TradingOrderBudgetAllocator.Candidate infinite = candidate(StrategyType.INFINITE, "1000.00");
        TradingOrderBudgetAllocator.Candidate privacy = candidate(StrategyType.PRIVACY, "1000.00");

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(privacy, infinite, vr), tradeDate);

        assertThat(result.approved()).containsExactly(vr, infinite);
        assertThat(result.rejectedBuy()).containsExactly(privacy);
    }

    @Test
    void allocate_sameStrategyTypeApprovesSmallerBuyTotalFirst() {
        when(liveBalancePort.getLiveBalance(eq(account.toBrokerRef()), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(100, new BigDecimal("20.00"), new BigDecimal("1000.00")));

        TradingOrderBudgetAllocator.Candidate large = candidate(StrategyType.INFINITE, "1200.00");
        TradingOrderBudgetAllocator.Candidate small = candidate(StrategyType.INFINITE, "800.00");

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(large, small), tradeDate);

        assertThat(result.approved()).containsExactly(small);
        assertThat(result.rejectedBuy()).containsExactly(large);
    }

    @Test
    void allocate_prioritizesVrThenInfiniteThenPrivacyForLimitedSellableQuantity() {
        when(sellableQuantityPort.getSellableQuantity(eq(StrategyTicker.SOXL), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 3));

        TradingOrderBudgetAllocator.Candidate vr = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"),
                StrategyType.VR, sell("25.00", 2));
        TradingOrderBudgetAllocator.Candidate infinite = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                StrategyType.INFINITE, sell("25.00", 2));
        TradingOrderBudgetAllocator.Candidate privacy = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                StrategyType.PRIVACY, sell("25.00", 2));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(privacy, infinite, vr), tradeDate);

        assertThat(result.approved()).containsExactly(vr);
        assertThat(result.rejectedSell()).containsExactly(infinite, privacy);
    }

    @Test
    void allocate_sameStrategyTypeApprovesSmallerSellQuantityFirst() {
        when(sellableQuantityPort.getSellableQuantity(eq(StrategyTicker.SOXL), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 4));

        TradingOrderBudgetAllocator.Candidate large = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                StrategyType.INFINITE, sell("25.00", 4));
        TradingOrderBudgetAllocator.Candidate small = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                StrategyType.INFINITE, sell("25.00", 2));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(large, small), tradeDate);

        assertThat(result.approved()).containsExactly(small);
        assertThat(result.rejectedSell()).containsExactly(large);
    }

    @Test
    void allocate_sameStrategyTypeAndSellQuantityApprovesLowerStrategyUuidFirst() {
        when(sellableQuantityPort.getSellableQuantity(eq(StrategyTicker.SOXL), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 2));

        TradingOrderBudgetAllocator.Candidate lowerStrategyId = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                StrategyType.INFINITE, sell("25.00", 2));
        TradingOrderBudgetAllocator.Candidate higherStrategyId = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                StrategyType.INFINITE, sell("25.00", 2));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(
                List.of(higherStrategyId, lowerStrategyId), tradeDate);

        assertThat(result.approved()).containsExactly(lowerStrategyId);
        assertThat(result.rejectedSell()).containsExactly(higherStrategyId);
    }

    @Test
    void allocate_sameStrategyTypeAndSellQuantityAndStrategyUuidUsesLowerCycleUuid() {
        when(sellableQuantityPort.getSellableQuantity(eq(StrategyTicker.SOXL), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 2));

        UUID strategyId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        TradingOrderBudgetAllocator.Candidate lowerCycleId = candidate(
                strategyId,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                StrategyType.INFINITE, sell("25.00", 2));
        TradingOrderBudgetAllocator.Candidate higherCycleId = candidate(
                strategyId,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                StrategyType.INFINITE, sell("25.00", 2));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(
                List.of(higherCycleId, lowerCycleId), tradeDate);

        assertThat(result.approved()).containsExactly(lowerCycleId);
        assertThat(result.rejectedSell()).containsExactly(higherCycleId);
    }

    @Test
    void allocate_buyIsAllOrNothingWithinCycle() {
        when(liveBalancePort.getLiveBalance(eq(account.toBrokerRef()), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(100, new BigDecimal("20.00"), new BigDecimal("1000.00")));

        TradingOrderBudgetAllocator.Candidate candidate = candidate(StrategyType.INFINITE,
                buy("700.00"), buy("400.00"));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).isEmpty();
        assertThat(result.rejectedBuy()).containsExactly(candidate);
    }

    @Test
    void allocate_sellDoesNotConsumeBuyBudget() {
        when(sellableQuantityPort.getSellableQuantity(eq(StrategyTicker.SOXL), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 10));

        TradingOrderBudgetAllocator.Candidate sellOnly = candidate(StrategyType.PRIVACY,
                sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(sellOnly), tradeDate);

        assertThat(result.approved()).containsExactly(sellOnly);
        assertThat(result.rejectedBuy()).isEmpty();
        assertThat(result.rejectedSell()).isEmpty();
    }

    @Test
    void allocate_rejectsSellWhenSellableQuantityIsInsufficient() {
        when(sellableQuantityPort.getSellableQuantity(eq(StrategyTicker.SOXL), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 2));

        TradingOrderBudgetAllocator.Candidate tooMuchSell = candidate(StrategyType.VR,
                sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(tooMuchSell), tradeDate);

        assertThat(result.approved()).isEmpty();
        assertThat(result.rejectedSell()).containsExactly(tooMuchSell);
    }

    @Test
    void allocate_rejectsSellWhenExistingReservationsLeaveInsufficientQuantity() {
        when(sellableQuantityPort.getSellableQuantity(eq(StrategyTicker.SOXL), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 5));
        when(orderPort.sumPlannedOrPlacedSellQuantityByAccountAndDateAndTicker(
                account.id(), tradeDate, StrategyTicker.SOXL)).thenReturn(3);

        TradingOrderBudgetAllocator.Candidate candidate = candidate(StrategyType.VR, sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).isEmpty();
        assertThat(result.rejectedSell()).containsExactly(candidate);
    }

    @Test
    void allocate_rejectsLaterSellsWhenAccountTickerTotalExceedsSellableQuantity() {
        when(sellableQuantityPort.getSellableQuantity(eq(StrategyTicker.SOXL), eq(account.toBrokerRef())))
                .thenReturn(new SellableQuantity("SOXL", 5));

        TradingOrderBudgetAllocator.Candidate first = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                StrategyType.PRIVACY, sell("25.00", 3));
        TradingOrderBudgetAllocator.Candidate second = candidate(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                StrategyType.INFINITE, sell("25.00", 3));

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(second, first), tradeDate);

        assertThat(result.approved()).containsExactly(second);
        assertThat(result.rejectedSell()).containsExactly(first);
    }

    @Test
    void allocate_preservesOriginalOrderSequenceWhenBothDirectionsAreApproved() {
        when(liveBalancePort.getLiveBalance(eq(account.toBrokerRef()), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(100, new BigDecimal("20.00"), new BigDecimal("1000.00")));

        Order firstBuy = buy("100.00");
        Order sell = sell("25.00", 3);
        Order secondBuy = buy("200.00");
        TradingOrderBudgetAllocator.Candidate candidate = candidate(
                StrategyType.INFINITE, firstBuy, sell, secondBuy);

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).singleElement()
                .satisfies(approved -> assertThat(approved.orders())
                        .containsExactly(firstBuy, sell, secondBuy));
    }

    @Test
    void allocate_keepsOnlyApprovedDirectionInOriginalOrderForPartialApproval() {
        when(liveBalancePort.getLiveBalance(eq(account.toBrokerRef()), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(100, new BigDecimal("20.00"), new BigDecimal("150.00")));

        Order firstBuy = buy("100.00");
        Order firstSell = sell("25.00", 3);
        Order secondBuy = buy("100.00");
        Order secondSell = sell("26.00", 2);
        TradingOrderBudgetAllocator.Candidate candidate = candidate(
                StrategyType.INFINITE, firstBuy, firstSell, secondBuy, secondSell);

        TradingOrderBudgetAllocator.Allocation result = allocator.allocate(List.of(candidate), tradeDate);

        assertThat(result.approved()).singleElement()
                .satisfies(approved -> assertThat(approved.orders())
                        .containsExactly(firstSell, secondSell));
        assertThat(result.rejectedBuy()).singleElement()
                .satisfies(rejected -> assertThat(rejected.orders())
                        .containsExactly(firstBuy, secondBuy));
    }

    @Test
    void fetchLiveQuotes_capturesFailurePerAccountAndAllocateRethrowsOriginalException() throws InterruptedException {
        RuntimeException brokerFailure = new IllegalStateException("KIS 잔고 조회 실패");
        when(liveBalancePort.getLiveBalance(eq(account.toBrokerRef()), eq(StrategyTicker.SOXL))).thenThrow(brokerFailure);

        TradingOrderBudgetAllocator.Candidate candidate = candidate(StrategyType.INFINITE, "1000.00");
        TradingOrderBudgetAllocator.LiveQuotes quotes =
                allocator.fetchLiveQuotes(List.of(List.of(candidate)));
        TradingOrderBudgetAllocator.AccountQuote quote = quotes.require(account.id());

        assertThat(quote.failure()).isSameAs(brokerFailure);
        assertThatThrownBy(() -> allocator.allocate(List.of(candidate), tradeDate, quote))
                .isSameAs(brokerFailure);
    }

    @Test
    void allocate_withPrefetchedQuoteDoesNotCallRegistryAgain() throws InterruptedException {
        when(liveBalancePort.getLiveBalance(eq(account.toBrokerRef()), eq(StrategyTicker.SOXL)))
                .thenReturn(new BrokerBalance(100, new BigDecimal("20.00"), new BigDecimal("3000.00")));

        TradingOrderBudgetAllocator.Candidate candidate = candidate(StrategyType.INFINITE, "500.00");
        TradingOrderBudgetAllocator.LiveQuotes quotes =
                allocator.fetchLiveQuotes(List.of(List.of(candidate)));
        TradingOrderBudgetAllocator.Allocation result =
                allocator.allocate(List.of(candidate), tradeDate, quotes.require(account.id()));

        assertThat(result.approved()).containsExactly(candidate);
        // fetchLiveQuotes 단계에서 1회만 조회하고, allocate 단계에서는 quote를 재사용해 재조회하지 않는다
        verify(liveBalancePort, times(1)).getLiveBalance(eq(account.toBrokerRef()), eq(StrategyTicker.SOXL));
    }

    private TradingOrderBudgetAllocator.Candidate candidate(StrategyType type, String buyAmount) {
        return candidate(type, buy(buyAmount));
    }

    private TradingOrderBudgetAllocator.Candidate candidate(StrategyType type, Order... orders) {
        return candidate(UUID.randomUUID(), UUID.randomUUID(), type, orders);
    }

    private TradingOrderBudgetAllocator.Candidate candidate(UUID strategyId, UUID cycleId, StrategyType type,
                                                             Order... orders) {
        StrategyRef strategy = new StrategyRef(strategyId, account.id(), type,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategy.id(), UUID.randomUUID(),
                new BigDecimal("1000.00"), null, tradeDate, null, null, null);
        return new TradingOrderBudgetAllocator.Candidate(
                new BatchContext(strategy, cycle, account, user),
                List.of(orders));
    }

    private Order buy(String amount) {
        return new Order(null, null, null, tradeDate, StrategyTicker.SOXL, Order.OrderType.LIMIT,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal(amount),
                Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order sell(String price, int quantity) {
        return new Order(null, null, null, tradeDate, StrategyTicker.SOXL, Order.OrderType.LIMIT,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.SELL, quantity, new BigDecimal(price),
                Order.OrderStatus.PLANNED, null, null, null);
    }
}
