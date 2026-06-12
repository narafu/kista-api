package com.kista.adapter.out.toss;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TosOrderApi 단위 테스트")
class TosOrderApiTest {

    @Mock TossHttpClient tossHttpClient;
    TosOrderApi tosOrderApi;

    static final Account ACCOUNT = new Account(
        UUID.randomUUID(), UUID.randomUUID(), "테스트",
        "12345678901", "cid", "csecret", "1", Account.Broker.TOSS
    );

    @BeforeEach
    void setUp() {
        // buildHeaders가 null 반환 시 NPE 방지 — 빈 헤더 반환
        when(tossHttpClient.buildHeaders(any())).thenReturn(new HttpHeaders());
        tosOrderApi = new TosOrderApi(tossHttpClient);
    }

    @Test
    @DisplayName("LOC 주문 → orderType=LIMIT, timeInForce=CLS, PLACED 상태 반환")
    void place_loc_mapsToLimitCls() {
        Order order = locBuyOrder();
        when(tossHttpClient.post(anyString(), any(), any(), eq(TosOrderApi.OrderResponse.class)))
            .thenReturn(new TosOrderApi.OrderResponse("toss-order-id", null));

        Order placed = tosOrderApi.place(order, ACCOUNT);

        // PLACED 상태, externalOrderId 설정 확인
        assertThat(placed.externalOrderId()).isEqualTo("toss-order-id");
        assertThat(placed.status()).isEqualTo(Order.OrderStatus.PLACED);

        // 요청 body 검증
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tossHttpClient).post(anyString(), any(), captor.capture(), any());
        Map<String, Object> body = captor.getValue();
        assertThat(body.get("orderType")).isEqualTo("LIMIT");
        assertThat(body.get("timeInForce")).isEqualTo("CLS");
    }

    @Test
    @DisplayName("MOC 주문 → timeInForce=CLS, price=0.01 (장마감 LIMIT 대체)")
    void place_moc_usesLimitClsWithMinPrice() {
        Order order = mocSellOrder();
        when(tossHttpClient.post(anyString(), any(), any(), eq(TosOrderApi.OrderResponse.class)))
            .thenReturn(new TosOrderApi.OrderResponse("toss-order-id-2", null));

        tosOrderApi.place(order, ACCOUNT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tossHttpClient).post(anyString(), any(), captor.capture(), any());
        Map<String, Object> body = captor.getValue();
        assertThat(body.get("timeInForce")).isEqualTo("CLS");
        assertThat(body.get("price")).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("LIMIT 주문 → timeInForce=DAY")
    void place_limit_mapsToLimitDay() {
        Order order = limitBuyOrder();
        when(tossHttpClient.post(anyString(), any(), any(), eq(TosOrderApi.OrderResponse.class)))
            .thenReturn(new TosOrderApi.OrderResponse("toss-order-id-3", null));

        tosOrderApi.place(order, ACCOUNT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tossHttpClient).post(anyString(), any(), captor.capture(), any());
        assertThat(captor.getValue().get("timeInForce")).isEqualTo("DAY");
    }

    @Test
    @DisplayName("응답 orderId null → TossApiException")
    void place_nullOrderId_throwsTossApiException() {
        Order order = locBuyOrder();
        when(tossHttpClient.post(anyString(), any(), any(), eq(TosOrderApi.OrderResponse.class)))
            .thenReturn(new TosOrderApi.OrderResponse(null, null));

        assertThatThrownBy(() -> tosOrderApi.place(order, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("취소: DELETE /api/v1/orders/{externalOrderId}")
    void cancel_callsDeleteWithOrderId() {
        Order order = new Order(UUID.randomUUID(), null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderDirection.BUY, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLACED, "toss-oid-123", null, null);

        tosOrderApi.cancel(order, ACCOUNT);

        verify(tossHttpClient).delete(eq("/api/v1/orders/toss-oid-123"), any());
    }

    // --- helpers ---

    private Order locBuyOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LOC, Order.OrderDirection.BUY, 2, new BigDecimal("25.50"),
            Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order mocSellOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.MOC, Order.OrderDirection.SELL, 1, BigDecimal.ZERO,
            Order.OrderStatus.PLANNED, null, null, null);
    }

    private Order limitBuyOrder() {
        return new Order(null, null, null, LocalDate.now(), Ticker.SOXL,
            Order.OrderType.LIMIT, Order.OrderDirection.BUY, 1, new BigDecimal("25.00"),
            Order.OrderStatus.PLANNED, null, null, null);
    }
}
