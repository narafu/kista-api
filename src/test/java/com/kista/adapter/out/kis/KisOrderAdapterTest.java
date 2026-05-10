package com.kista.adapter.out.kis;

import com.kista.domain.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisOrderAdapter 주문 처리 검증")
class KisOrderAdapterTest {

    @Mock KisHttpClient kisHttpClient;
    @InjectMocks KisOrderAdapter adapter;

    private static final LocalDate TRADE_DATE = LocalDate.of(2024, 6, 15);

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", "01",
            Strategy.INFINITE, StrategyStatus.ACTIVE,
            null, null, "SOXL", "AMS", Instant.now(), Instant.now()
    );

    @BeforeEach
    void setUp() {
        when(kisHttpClient.buildHeaders(anyString(), any(Account.class))).thenReturn(new HttpHeaders());
    }

    @Test
    @DisplayName("BUY+LOC: TTTT1002U 사용, ORD_DVSN=34, 가격=0, 상태=PLACED")
    void place_buyLoc_usesBuyTrIdAndOrdDvsn34() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LOC, Order.OrderDirection.BUY,
                10, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.place(order, ACCOUNT);

        verify(kisHttpClient).buildHeaders(eq("TTTT1002U"), eq(ACCOUNT));
        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        Map<?, ?> body = bodyCaptor.getValue();
        assertThat(body.get("ORD_DVSN")).isEqualTo("34");
        assertThat(body.get("OVRS_ORD_UNPR")).isEqualTo("0");
    }

    @Test
    @DisplayName("BUY+MOC: ORD_DVSN=33, 가격=0")
    void place_buyMoc_usesOrdDvsn33() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.MOC, Order.OrderDirection.BUY,
                5, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.place(order, ACCOUNT);

        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue().get("ORD_DVSN")).isEqualTo("33");
        assertThat(bodyCaptor.getValue().get("OVRS_ORD_UNPR")).isEqualTo("0");
    }

    @Test
    @DisplayName("BUY+LIMIT: ORD_DVSN=00, 실제 가격 전달")
    void place_buyLimit_usesActualPrice() {
        BigDecimal limitPrice = new BigDecimal("25.50");
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LIMIT, Order.OrderDirection.BUY,
                3, limitPrice, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.place(order, ACCOUNT);

        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue().get("ORD_DVSN")).isEqualTo("00");
        assertThat(bodyCaptor.getValue().get("OVRS_ORD_UNPR")).isEqualTo("25.50");
    }

    @Test
    @DisplayName("SELL: TTTT1006U 사용")
    void place_sell_usesSellTrId() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LOC, Order.OrderDirection.SELL,
                8, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        adapter.place(order, ACCOUNT);

        verify(kisHttpClient).buildHeaders(eq("TTTT1006U"), eq(ACCOUNT));
    }

    @Test
    @DisplayName("응답 ODNO → kisOrderId 반환, 상태=PLACED")
    void place_responseWithOdno_returnsKisOrderId() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LOC, Order.OrderDirection.BUY,
                10, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        KisOrderAdapter.OrderResponse response =
                new KisOrderAdapter.OrderResponse(new KisOrderAdapter.OrderResponse.Output("ORD123"));
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(response);

        Order result = adapter.place(order, ACCOUNT);

        assertThat(result.kisOrderId()).isEqualTo("ORD123");
        assertThat(result.status()).isEqualTo(Order.OrderStatus.PLACED);
    }

    @Test
    @DisplayName("null 응답: kisOrderId=null, 상태=PLACED")
    void place_nullResponse_returnsNullKisOrderId() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LOC, Order.OrderDirection.BUY,
                10, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        Order result = adapter.place(order, ACCOUNT);

        assertThat(result.kisOrderId()).isNull();
        assertThat(result.status()).isEqualTo(Order.OrderStatus.PLACED);
    }
}
