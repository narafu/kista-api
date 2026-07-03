package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.broker.Execution;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.Order.OrderDirection;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.ParameterizedTypeReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossOrderApi 단위 테스트")
class TossOrderApiTest {

    @Mock TossHttpClient tossHttpClient;
    TossOrderApi tossOrderApi;

    static final Account ACCOUNT = new Account(
        UUID.randomUUID(), UUID.randomUUID(), "테스트",
        "12345678901", "cid", "csecret", "1", Account.Broker.TOSS, null
    );

    @BeforeEach
    void setUp() {
        tossOrderApi = new TossOrderApi(tossHttpClient);
    }

    // Toss API 응답 TossResult<OrderResponse> 래퍼 헬퍼
    private static TossResult<TossOrderApi.OrderResponse> wrap(String orderId) {
        return new TossResult<>(new TossOrderApi.OrderResponse(orderId, null));
    }

    // GET /api/v1/orders 응답 TossResult<OrdersResponse> 래퍼 헬퍼
    private static TossResult<TossOrderApi.OrdersResponse> wrapOrders(TossOrderApi.OrdersResponse response) {
        return new TossResult<>(response);
    }

    @Test
    @DisplayName("LOC 주문 → orderType=LIMIT, timeInForce=CLS, PLACED 상태 반환")
    void place_loc_mapsToLimitCls() {
        Order order = locBuyOrder();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap("toss-order-id"));

        Order placed = tossOrderApi.place(order, ACCOUNT);

        // PLACED 상태, externalOrderId 설정 확인
        assertThat(placed.externalOrderId()).isEqualTo("toss-order-id");
        assertThat(placed.status()).isEqualTo(Order.OrderStatus.PLACED);

        // 요청 body 검증
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tossHttpClient).post(anyString(), any(), captor.capture(), any(ParameterizedTypeReference.class));
        Map<String, Object> body = captor.getValue();
        assertThat(body.get("orderType")).isEqualTo("LIMIT");
        assertThat(body.get("timeInForce")).isEqualTo("CLS");
    }

    @Test
    @DisplayName("MOC 주문 → timeInForce=CLS, price=0.01 (장마감 LIMIT 대체)")
    void place_moc_usesLimitClsWithMinPrice() {
        Order order = mocSellOrder();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap("toss-order-id-2"));

        tossOrderApi.place(order, ACCOUNT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tossHttpClient).post(anyString(), any(), captor.capture(), any(ParameterizedTypeReference.class));
        Map<String, Object> body = captor.getValue();
        assertThat(body.get("timeInForce")).isEqualTo("CLS");
        assertThat(body.get("price")).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("LIMIT 주문 → timeInForce=DAY")
    void place_limit_mapsToLimitDay() {
        Order order = limitBuyOrder();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap("toss-order-id-3"));

        tossOrderApi.place(order, ACCOUNT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tossHttpClient).post(anyString(), any(), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().get("timeInForce")).isEqualTo("DAY");
    }

    @Test
    @DisplayName("응답 orderId null → TossApiException")
    void place_nullOrderId_throwsTossApiException() {
        Order order = locBuyOrder();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossOrderApi.OrderResponse(null, null)));

        assertThatThrownBy(() -> tossOrderApi.place(order, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("취소: DELETE /api/v1/orders/{externalOrderId}")
    void cancel_callsDeleteWithOrderId() {
        Order order = new Order(UUID.randomUUID(), null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLACED, "toss-oid-123", null, null);

        tossOrderApi.cancel(order, ACCOUNT);

        verify(tossHttpClient).delete(eq("/api/v1/orders/toss-oid-123"), any());
    }

    @Test
    @DisplayName("CLOSED 체결 → Execution 변환 (filledQuantity>0인 주문만)")
    void getExecutions_closed_convertsFilledOrders() {
        TossOrderApi.OrderExecutionItem exec = new TossOrderApi.OrderExecutionItem("3", "25.50", "76.50", null);
        TossOrderApi.OrderItem item = new TossOrderApi.OrderItem("oid-1", "SOXL", "BUY", "FILLED", exec);
        TossOrderApi.OrdersResponse closedResp = new TossOrderApi.OrdersResponse(List.of(item), null, false);
        TossOrderApi.OrdersResponse openResp   = new TossOrderApi.OrdersResponse(List.of(), null, false);

        // CLOSED 먼저, OPEN 두 번째로 반환
        when(tossHttpClient.get(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrapOrders(closedResp))
            .thenReturn(wrapOrders(openResp));

        List<Execution> executions = tossOrderApi.getExecutions(
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), Ticker.SOXL, ACCOUNT);

        assertThat(executions).hasSize(1);
        Execution e = executions.get(0);
        assertThat(e.quantity()).isEqualTo(3);
        assertThat(e.price()).isEqualByComparingTo("25.50");
        assertThat(e.amountUsd()).isEqualByComparingTo("76.50");
        assertThat(e.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(e.externalOrderId()).isEqualTo("oid-1");
        assertThat(e.ticker()).isEqualTo(Ticker.SOXL);
    }

    @Test
    @DisplayName("filledQuantity=0 또는 null인 주문은 Execution에서 제외")
    void getExecutions_skipsUnfilledOrders() {
        TossOrderApi.OrderExecutionItem noFill   = new TossOrderApi.OrderExecutionItem("0",  null, null, null);
        TossOrderApi.OrderExecutionItem nullFill = new TossOrderApi.OrderExecutionItem(null, null, null, null);
        TossOrderApi.OrderItem unfilledItem  = new TossOrderApi.OrderItem("oid-2", "SOXL", "BUY", "PENDING", noFill);
        TossOrderApi.OrderItem nullFillItem  = new TossOrderApi.OrderItem("oid-3", "SOXL", "BUY", "PENDING", nullFill);
        TossOrderApi.OrdersResponse closedResp = new TossOrderApi.OrdersResponse(List.of(unfilledItem, nullFillItem), null, false);
        TossOrderApi.OrdersResponse openResp   = new TossOrderApi.OrdersResponse(List.of(), null, false);

        when(tossHttpClient.get(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrapOrders(closedResp))
            .thenReturn(wrapOrders(openResp));

        List<Execution> result = tossOrderApi.getExecutions(
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), Ticker.SOXL, ACCOUNT);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("OPEN 상태 부분 체결 → Execution 포함")
    void getExecutions_open_partialFilled_included() {
        TossOrderApi.OrderExecutionItem exec = new TossOrderApi.OrderExecutionItem("2", "30.00", "60.00", null);
        TossOrderApi.OrderItem partial = new TossOrderApi.OrderItem("oid-4", "SOXL", "SELL", "PARTIAL_FILLED", exec);
        TossOrderApi.OrdersResponse closedResp = new TossOrderApi.OrdersResponse(List.of(), null, false);
        TossOrderApi.OrdersResponse openResp   = new TossOrderApi.OrdersResponse(List.of(partial), null, false);

        when(tossHttpClient.get(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrapOrders(closedResp))
            .thenReturn(wrapOrders(openResp));

        List<Execution> result = tossOrderApi.getExecutions(
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), Ticker.SOXL, ACCOUNT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).direction()).isEqualTo(OrderDirection.SELL);
        assertThat(result.get(0).quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("averageFilledPrice=null → price=ZERO, amountUsd는 명시값 우선")
    void getExecutions_nullPrice_fallbackAmount() {
        // amountUsd="50.00", price=null → price=BigDecimal.ZERO, amountUsd="50.00"(명시값 우선)
        TossOrderApi.OrderExecutionItem exec = new TossOrderApi.OrderExecutionItem("2", null, "50.00", null);
        TossOrderApi.OrderItem item = new TossOrderApi.OrderItem("oid-5", "SOXL", "BUY", "FILLED", exec);
        TossOrderApi.OrdersResponse closedResp = new TossOrderApi.OrdersResponse(List.of(item), null, false);
        TossOrderApi.OrdersResponse openResp   = new TossOrderApi.OrdersResponse(List.of(), null, false);

        when(tossHttpClient.get(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrapOrders(closedResp))
            .thenReturn(wrapOrders(openResp));

        List<Execution> result = tossOrderApi.getExecutions(
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), Ticker.SOXL, ACCOUNT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amountUsd()).isEqualByComparingTo("50.00");
    }

    // --- helpers ---

    private Order locBuyOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 2, new BigDecimal("25.50"),
            Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order mocSellOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.MOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL, 1, BigDecimal.ZERO,
            Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order limitBuyOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LIMIT, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLANNED, null, null, null);
    }
}
