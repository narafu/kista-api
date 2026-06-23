package com.kista.adapter.out.kis;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KisOrderApi 주문 처리 검증")
class KisOrderApiTest {

    @Mock KisHttpClient kisHttpClient;
    @Spy KisExchangeRegistry exchangeRegistry = new KisExchangeRegistry();
    @InjectMocks KisOrderApi api;

    private static final LocalDate TRADE_DATE = LocalDate.of(2024, 6, 15);

    private static final Account ACCOUNT = new Account(
            UUID.randomUUID(), UUID.randomUUID(), "테스트계좌",
            "74420614", "appKey", "appSecret", null,
            Account.Broker.KIS, null
    );

    @BeforeEach
    void setUp() {
        when(kisHttpClient.buildHeaders(anyString(), any(Account.class))).thenReturn(new HttpHeaders());
    }

    @Test
    @DisplayName("BUY+LOC: TTTT1002U 사용, ORD_DVSN=34, 실제 가격 전달(지정가이므로 0 금지)")
    void place_buyLoc_usesBuyTrIdAndOrdDvsn34() {
        BigDecimal locPrice = new BigDecimal("25.50");
        Order order = new Order(null, null, null, TRADE_DATE, Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                10, locPrice, Order.OrderStatus.PLACED, null, null, null);
        KisOrderApi.OrderResponse ok =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD"));
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(ok);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        api.place(order, ACCOUNT);

        verify(kisHttpClient).buildHeaders(eq("TTTT1002U"), eq(ACCOUNT));
        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue()).contains("\"ORD_DVSN\": \"34\"");
        assertThat(bodyCaptor.getValue()).contains("\"OVRS_ORD_UNPR\": \"25.50\"");
    }

    @Test
    @DisplayName("BUY+MOC: ORD_DVSN=33, 가격=0")
    void place_buyMoc_usesOrdDvsn33() {
        Order order = new Order(null, null, null, TRADE_DATE, Ticker.SOXL, Order.OrderType.MOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.BUY,
                5, BigDecimal.ZERO, Order.OrderStatus.PLACED, null, null, null);
        KisOrderApi.OrderResponse ok =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD"));
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(ok);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        api.place(order, ACCOUNT);

        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue()).contains("\"ORD_DVSN\": \"33\"");
        assertThat(bodyCaptor.getValue()).contains("\"OVRS_ORD_UNPR\": \"0\"");
    }

    @Test
    @DisplayName("BUY+LIMIT: ORD_DVSN=00, 실제 가격 전달")
    void place_buyLimit_usesActualPrice() {
        BigDecimal limitPrice = new BigDecimal("25.50");
        Order order = new Order(null, null, null, TRADE_DATE, Ticker.SOXL, Order.OrderType.LIMIT, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                3, limitPrice, Order.OrderStatus.PLACED, null, null, null);
        KisOrderApi.OrderResponse ok =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD"));
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(ok);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        api.place(order, ACCOUNT);

        verify(kisHttpClient).post(anyString(), any(), bodyCaptor.capture(), any());
        assertThat(bodyCaptor.getValue()).contains("\"ORD_DVSN\": \"00\"");
        assertThat(bodyCaptor.getValue()).contains("\"OVRS_ORD_UNPR\": \"25.50\"");
    }

    @Test
    @DisplayName("SELL: TTTT1006U 사용")
    void place_sell_usesSellTrId() {
        Order order = new Order(null, null, null, TRADE_DATE, Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_OPEN, Order.OrderDirection.SELL,
                8, BigDecimal.ZERO, Order.OrderStatus.PLACED, null, null, null);
        KisOrderApi.OrderResponse ok =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD"));
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(ok);

        api.place(order, ACCOUNT);

        verify(kisHttpClient).buildHeaders(eq("TTTT1006U"), eq(ACCOUNT));
    }

    @Test
    @DisplayName("응답 ODNO → orderId 반환, 상태=PLACED")
    void place_responseWithOdno_returnsExternalOrderId() {
        Order order = new Order(null, null, null, TRADE_DATE, Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                10, BigDecimal.ZERO, Order.OrderStatus.PLACED, null, null, null);
        KisOrderApi.OrderResponse response =
                new KisOrderApi.OrderResponse("0", "KISC0000", "정상처리", new KisOrderApi.OrderResponse.Output("ORD123"));
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(response);

        Order result = api.place(order, ACCOUNT);

        assertThat(result.externalOrderId()).isEqualTo("ORD123");
        assertThat(result.status()).isEqualTo(Order.OrderStatus.PLACED);
    }

    @Test
    @DisplayName("KIS 비즈니스 오류(rt_cd!=0): KisApiException 발생")
    void place_kisErrorResponse_throwsKisApiException() {
        Order order = new Order(null, null, null, TRADE_DATE, Ticker.SOXL, Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY,
                10, BigDecimal.ZERO, Order.OrderStatus.PLACED, null, null, null);
        KisOrderApi.OrderResponse errorResponse =
                new KisOrderApi.OrderResponse("1", "EGW00202", "GW라우팅 중 오류가 발생했습니다.", null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(errorResponse);

        assertThatThrownBy(() -> api.place(order, ACCOUNT))
                .isInstanceOf(KisApiException.class)
                .hasMessageContaining("EGW00202");
    }

    @Test
    @DisplayName("cancel: TTTT1004U + CANCEL_PATH 호출, RVSE_CNCL_DVSN_CD=02, ORGN_ODNO=기존주문번호")
    void cancel_sendsCorrectParameters() {
        Order order = new Order(UUID.randomUUID(), ACCOUNT.id(), UUID.randomUUID(), TRADE_DATE, Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 10, new BigDecimal("25.50"),
                Order.OrderStatus.PLACED, "ORD_123", null, null);
        when(kisHttpClient.post(anyString(), any(), any(), any())).thenReturn(null);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        api.cancel(order, ACCOUNT);

        verify(kisHttpClient).buildHeaders(eq("TTTT1004U"), eq(ACCOUNT));
        verify(kisHttpClient).post(
                eq("/uapi/overseas-stock/v1/trading/order-rvsecncl"),
                any(), bodyCaptor.capture(), any());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("\"RVSE_CNCL_DVSN_CD\": \"02\"");
        assertThat(body).contains("\"ORGN_ODNO\": \"ORD_123\"");
        assertThat(body).contains("\"ORD_QTY\": \"0\"");
        assertThat(body).contains("\"OVRS_ORD_UNPR\": \"0\"");
    }

    @Test
    @DisplayName("cancel: KIS 오류(RuntimeException) 전파")
    void cancel_kisError_propagatesException() {
        Order order = new Order(UUID.randomUUID(), ACCOUNT.id(), UUID.randomUUID(), TRADE_DATE, Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderTiming.AT_CLOSE, Order.OrderDirection.BUY, 10, new BigDecimal("25.50"),
                Order.OrderStatus.PLACED, "ORD_456", null, null);
        when(kisHttpClient.post(anyString(), any(), any(), any()))
                .thenThrow(new RuntimeException("KIS 오류"));

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> api.cancel(order, ACCOUNT));
    }
}
