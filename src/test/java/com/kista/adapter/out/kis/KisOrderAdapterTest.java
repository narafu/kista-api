package com.kista.adapter.out.kis;

import com.kista.domain.model.Order;
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
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisOrderAdapter 주문 처리 검증")
class KisOrderAdapterTest {

    @Mock
    KisHttpClient kisHttpClient;

    @InjectMocks
    KisOrderAdapter adapter;

    private static final KisProperties TEST_PROPS = new KisProperties(
            "https://api.test.com", "key", "secret", "12345678", "01", "SOXL", "NAS"
    );
    private static final LocalDate TRADE_DATE = LocalDate.of(2024, 6, 15);
    private static final String TOKEN = "test-token";

    @BeforeEach
    void setUp() {
        when(kisHttpClient.props()).thenReturn(TEST_PROPS);
        when(kisHttpClient.buildHeaders(anyString(), anyString())).thenReturn(new HttpHeaders());
    }

    @Test
    @DisplayName("BUY+LOC: TTTS0308U 사용, ORD_DVSN=32, 가격=0, 상태=PLACED")
    void place_buyLoc_usesBuyTrIdAndOrdDvsn32() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LOC, Order.OrderDirection.BUY,
                10, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.place(TOKEN, order);

        verify(kisHttpClient).buildHeaders(eq(TOKEN), eq("TTTS0308U"));
        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        Map<?, ?> body = bodyCaptor.getValue();
        assertThat(body.get("ORD_DVSN")).isEqualTo("32");
        assertThat(body.get("OVRS_ORD_UNPR")).isEqualTo("0");
    }

    @Test
    @DisplayName("BUY+MOC: ORD_DVSN=34, 가격=0")
    void place_buyMoc_usesOrdDvsn34() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.MOC, Order.OrderDirection.BUY,
                5, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.place(TOKEN, order);

        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        Map<?, ?> body = bodyCaptor.getValue();
        assertThat(body.get("ORD_DVSN")).isEqualTo("34");
        assertThat(body.get("OVRS_ORD_UNPR")).isEqualTo("0");
    }

    @Test
    @DisplayName("BUY+LIMIT: ORD_DVSN=00, 실제 가격 전달")
    void place_buyLimit_usesActualPrice() {
        BigDecimal limitPrice = new BigDecimal("25.50");
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LIMIT, Order.OrderDirection.BUY,
                3, limitPrice, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        adapter.place(TOKEN, order);

        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        Map<?, ?> body = bodyCaptor.getValue();
        assertThat(body.get("ORD_DVSN")).isEqualTo("00");
        assertThat(body.get("OVRS_ORD_UNPR")).isEqualTo("25.50");
    }

    @Test
    @DisplayName("SELL: TTTS0307U 사용")
    void place_sell_usesSellTrId() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LOC, Order.OrderDirection.SELL,
                8, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        adapter.place(TOKEN, order);

        verify(kisHttpClient).buildHeaders(eq(TOKEN), eq("TTTS0307U"));
    }

    @Test
    @DisplayName("응답 ODNO → kisOrderId 반환, 상태=PLACED")
    void place_responseWithOdno_returnsKisOrderId() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LOC, Order.OrderDirection.BUY,
                10, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        KisOrderAdapter.OrderResponse response =
                new KisOrderAdapter.OrderResponse(new KisOrderAdapter.OrderResponse.Output("ORD123"));
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(response);

        Order result = adapter.place(TOKEN, order);

        assertThat(result.kisOrderId()).isEqualTo("ORD123");
        assertThat(result.status()).isEqualTo(Order.OrderStatus.PLACED);
    }

    @Test
    @DisplayName("null 응답: kisOrderId=null, 상태=PLACED")
    void place_nullResponse_returnsNullKisOrderId() {
        Order order = new Order(TRADE_DATE, "SOXL", Order.OrderType.LOC, Order.OrderDirection.BUY,
                10, BigDecimal.ZERO, Order.OrderStatus.PLACED, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        Order result = adapter.place(TOKEN, order);

        assertThat(result.kisOrderId()).isNull();
        assertThat(result.status()).isEqualTo(Order.OrderStatus.PLACED);
    }
}
