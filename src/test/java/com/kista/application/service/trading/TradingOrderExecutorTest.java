package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.out.NotifyPort;
import com.kista.domain.port.out.OrderPort;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.*;

// PLANNED → 증권사 접수 → PLACED 마킹 흐름과 가격 보정 호출 조건(currentPrice/position 둘 다 있을 때만) 검증
@ExtendWith(MockitoExtension.class)
@DisplayName("TradingOrderExecutor 단위 테스트")
class TradingOrderExecutorTest {

    @Mock OrderPort orderPort;
    @Mock BrokerOrderRouter brokerOrderRouter;
    @Mock BuyOrderPriceCapper buyOrderPriceCapper;
    @Mock NotifyPort notifyPort;

    static final LocalDate TODAY = LocalDate.now();

    static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "key", "secret", null,
            Account.Broker.KIS, null);

    static final UUID STRATEGY_CYCLE_ID = UUID.randomUUID();

    static final BigDecimal CURRENT_PRICE = new BigDecimal("50.00");

    static final InfinitePosition POSITION = new InfinitePosition(
            new AccountBalance(0, null, new BigDecimal("20000")), Ticker.SOXL, new BigDecimal("10.00"), 20);

    private TradingOrderExecutor executor() {
        return new TradingOrderExecutor(orderPort, brokerOrderRouter, buyOrderPriceCapper, notifyPort);
    }

    private Order planned(UUID id, Order.OrderDirection direction, String price, int quantity) {
        return new Order(id, ACCOUNT.id(), STRATEGY_CYCLE_ID, TODAY, Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, direction, quantity, new BigDecimal(price), Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order kisResponse(String externalOrderId) {
        // KIS 응답 Order는 id=null (DB PK는 호출측이 보존)
        return new Order(null, ACCOUNT.id(), STRATEGY_CYCLE_ID, TODAY, Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 10, new BigDecimal("50.00"), Order.OrderStatus.PLACED, externalOrderId, null, null);
    }

    @Test
    @DisplayName("currentPrice·position 모두 있으면 매수 가격 보정 후 접수")
    void placeOrders_withPriceAndPosition_capsBeforePlacing() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.BUY, "50.00", 10);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerOrderRouter.place(plannedOrder, ACCOUNT)).thenReturn(kisResponse("KIS-001"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION);

        verify(buyOrderPriceCapper).capIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().id()).isEqualTo(orderId); // DB PK 보존
        assertThat(result.getFirst().status()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(result.getFirst().externalOrderId()).isEqualTo("KIS-001");
        verify(orderPort).markPlaced(orderId, "KIS-001");
    }

    @Test
    @DisplayName("currentPrice가 없으면 가격 보정 생략 (수동 선행 주문 그대로 접수)")
    void placeOrders_withoutCurrentPrice_skipsCapping() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerOrderRouter.place(plannedOrder, ACCOUNT)).thenReturn(kisResponse("KIS-002"));

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, null, POSITION);

        verify(buyOrderPriceCapper, never()).capIfNeeded(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("position이 없으면 INFINITE 보정 생략 — PRIVACY 캡으로 대체")
    void placeOrders_withoutPosition_skipsInfiniteCapping() {
        UUID orderId = UUID.randomUUID();
        Order plannedOrder = planned(orderId, Order.OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(plannedOrder));
        when(brokerOrderRouter.place(plannedOrder, ACCOUNT)).thenReturn(kisResponse("KIS-003"));

        executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, null);

        // INFINITE 보정(capIfNeeded)은 호출되지 않음
        verify(buyOrderPriceCapper, never()).capIfNeeded(any(), any(), any(), any(), any());
        // PRIVACY 캡(capPrivacyIfNeeded)은 currentPrice가 있을 때 호출됨
        verify(buyOrderPriceCapper).capPrivacyIfNeeded(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE);
    }

    @Test
    @DisplayName("계획 주문이 없으면 빈 목록 반환 + KIS 접수 호출 없음")
    void placeOrders_noPlannedOrders_returnsEmpty() {
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of());

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION);

        assertThat(result).isEmpty();
        verify(brokerOrderRouter, never()).place(any(), any());
        verify(orderPort, never()).markPlaced(any(), any());
    }

    @Test
    @DisplayName("복수 계획 주문을 순서대로 접수하고 각각 PLACED 마킹")
    void placeOrders_multiplePlannedOrders_placesAllInOrder() {
        UUID id1 = UUID.randomUUID(), id2 = UUID.randomUUID();
        Order order1 = planned(id1, Order.OrderDirection.BUY, "50.00", 10);
        Order order2 = planned(id2, Order.OrderDirection.SELL, "60.00", 5);
        when(orderPort.findPlannedByCycleAndDate(STRATEGY_CYCLE_ID, TODAY)).thenReturn(List.of(order1, order2));
        when(brokerOrderRouter.place(order1, ACCOUNT)).thenReturn(kisResponse("KIS-101"));
        when(brokerOrderRouter.place(order2, ACCOUNT)).thenReturn(kisResponse("KIS-102"));

        List<Order> result = executor().placeOrders(TODAY, ACCOUNT, STRATEGY_CYCLE_ID, CURRENT_PRICE, POSITION);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(id1);
        assertThat(result.get(0).externalOrderId()).isEqualTo("KIS-101");
        assertThat(result.get(1).id()).isEqualTo(id2);
        assertThat(result.get(1).externalOrderId()).isEqualTo("KIS-102");
        verify(orderPort).markPlaced(id1, "KIS-101");
        verify(orderPort).markPlaced(id2, "KIS-102");
    }
}
