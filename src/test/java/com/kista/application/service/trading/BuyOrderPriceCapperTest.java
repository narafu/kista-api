package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.strategy.InfiniteStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

// BUY PLANNED 가격이 currentPrice × 1.10 초과 시 InfiniteStrategy에 위임 후 영속화 — I/O 오케스트레이션만 검증
// 캡 가격 재산정 공식(병합/보정 등)은 InfiniteStrategyTypeTest.buildCappedBuyOrders 참고
// PRIVACY: position 없이 단순 가격 캡만 적용 (capPrivacyIfNeeded)
@ExtendWith(MockitoExtension.class)
class BuyOrderPriceCapperTest {

    @Mock OrderPort orderPort;
    @Mock TradingOrderPlanner orderPlanner;
    @Mock InfiniteStrategy infiniteStrategy;
    @Captor ArgumentCaptor<List<Order>> ordersCaptor;
    @Captor ArgumentCaptor<BigDecimal> capCaptor;

    static final LocalDate TODAY = LocalDate.now();

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Account.Broker.KIS, null);

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    static final InfinitePosition POSITION = new InfinitePosition(
            new AccountBalance(0, null, new BigDecimal("20000")), Ticker.SOXL, new BigDecimal("10.00"), 20);

    private BuyOrderPriceCapper capper() {
        return new BuyOrderPriceCapper(orderPort, orderPlanner, infiniteStrategy);
    }

    private Order buy(String price, int quantity) {
        return new Order(null, null, null, TODAY, Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    @Test
    void noBuyOrders_doesNothing() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        capper().capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), POSITION);

        verify(infiniteStrategy, never()).buildCappedBuyOrders(any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void allBuysWithinCap_doesNothing() {
        // cap = 50 × 1.10 = 55.00 — 모든 BUY가 cap 이하라 보정 불필요
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 18)));

        capper().capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), POSITION);

        verify(infiniteStrategy, never()).buildCappedBuyOrders(any(), any(), any(), any());
        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void buysExceedCap_delegatesToStrategyAndPersistsResult() {
        // cap = 50 × 1.10 = 55.00
        List<Order> buyOrders = List.of(buy("60.00", 1), buy("52.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        List<Order> capped = List.of(buy("55.00", 9), buy("52.00", 11));
        when(infiniteStrategy.buildCappedBuyOrders(eq(POSITION), eq(TODAY), eq(buyOrders), any()))
                .thenReturn(capped);

        capper().capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), POSITION);

        verify(infiniteStrategy).buildCappedBuyOrders(eq(POSITION), eq(TODAY), eq(buyOrders), capCaptor.capture());
        assertThat(capCaptor.getValue()).isEqualByComparingTo("55.00");
        verify(orderPort, times(2)).markCancelled(isNull()); // 테스트 buy()의 id=null
        verify(orderPlanner).savePlannedOrders(ordersCaptor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        assertThat(ordersCaptor.getValue()).isEqualTo(capped);
    }

    @Test
    void cappedResultEmpty_deletesWithoutSaving() {
        List<Order> buyOrders = List.of(buy("200.00", 1));
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(buyOrders);
        when(infiniteStrategy.buildCappedBuyOrders(eq(POSITION), eq(TODAY), eq(buyOrders), any()))
                .thenReturn(List.of());

        capper().capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"), POSITION);

        verify(orderPort).markCancelled(isNull()); // 테스트 buy()의 id=null
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    // ─── PRIVACY 캡 (capPrivacyIfNeeded) ────────────────────────────────────────

    @Test
    void capPrivacyIfNeeded_noBuyOrders_doesNothing() {
        // PLANNED 주문이 없으면 아무 작업도 하지 않음
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        capper().capPrivacyIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"));

        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    void capPrivacyIfNeeded_allBuysWithinCap_doesNothing() {
        // cap = 50 × 1.10 = 55.00 — BUY 가격 50.00 ≤ 55.00 → 보정 불필요
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("50.00", 5)));

        capper().capPrivacyIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("50.00"));

        verify(orderPort, never()).markCancelled(any());
        verify(orderPlanner, never()).savePlannedOrders(any(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void capPrivacyIfNeeded_buysExceedCap_capsToCurrentPriceX110KeepingQuantity() {
        // currentPrice=30, cap=33.00 — FIDA 가격 40.00만 cap 초과, 28.00은 cap 이하라 그대로 유지
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY))
                .thenReturn(List.of(buy("40.00", 5), buy("28.00", 3)));

        capper().capPrivacyIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, new BigDecimal("30.00"));

        // cap 초과 주문(40.00) 1건만 CANCELLED, cap 이하(28.00)는 건드리지 않음
        verify(orderPort, times(1)).markCancelled(isNull());
        ArgumentCaptor<List<Order>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderPlanner).savePlannedOrders(captor.capture(), eq(ACCOUNT), eq(STRATEGY_CYCLE_ID));
        List<Order> saved = captor.getValue();
        // 40.00 → 33.00으로 보정, 수량 5 유지 (1건만 재저장)
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).price()).isEqualByComparingTo("33.00");
        assertThat(saved.get(0).quantity()).isEqualTo(5);
    }
}
