package com.kista.broker.adapter.out.toss;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.CancelInstruction;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.broker.domain.model.OrderInstruction;
import com.kista.broker.domain.model.OrderResult;
import com.kista.broker.domain.model.OrderType;
import com.kista.sharedkernel.Broker;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.broker.domain.model.toss.TossApiException;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TossOrderApi 단위 테스트")
class TossOrderApiTest {

    @Mock TossHttpClient tossHttpClient;
    TossOrderApi tossOrderApi;

    static final BrokerAccountRef ACCOUNT = new BrokerAccountRef(
        UUID.randomUUID(), "cid", "csecret",
        "12345678901", "1", Broker.TOSS
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
    @DisplayName("LOC 주문 → orderType=LIMIT, timeInForce=CLS, externalOrderId 반환")
    void place_loc_mapsToLimitCls() {
        OrderInstruction instruction = locBuyInstruction();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap("toss-order-id"));

        OrderResult result = tossOrderApi.place(instruction, ACCOUNT);

        // externalOrderId 설정 확인
        assertThat(result.externalOrderId()).isEqualTo("toss-order-id");

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
        OrderInstruction instruction = mocSellInstruction();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap("toss-order-id-2"));

        tossOrderApi.place(instruction, ACCOUNT);

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
        OrderInstruction instruction = limitBuyInstruction();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(wrap("toss-order-id-3"));

        tossOrderApi.place(instruction, ACCOUNT);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(tossHttpClient).post(anyString(), any(), captor.capture(), any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().get("timeInForce")).isEqualTo("DAY");
    }

    @Test
    @DisplayName("응답 orderId null → TossApiException")
    void place_nullOrderId_throwsTossApiException() {
        OrderInstruction instruction = locBuyInstruction();
        when(tossHttpClient.post(anyString(), any(), any(), any(ParameterizedTypeReference.class)))
            .thenReturn(new TossResult<>(new TossOrderApi.OrderResponse(null, null)));

        assertThatThrownBy(() -> tossOrderApi.place(instruction, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("취소: POST /api/v1/orders/{externalOrderId}/cancel")
    void cancel_callsPostCancelWithOrderId() {
        CancelInstruction instruction = new CancelInstruction(StrategyTicker.SOXL, "toss-oid-123");

        tossOrderApi.cancel(instruction, ACCOUNT);

        verify(tossHttpClient).post(eq("/api/v1/orders/toss-oid-123/cancel"), any(), any(), eq(Void.class));
    }

    @Test
    @DisplayName("취소 실패(500)는 그대로 전파된다")
    void cancel_serverError_rethrows() {
        CancelInstruction instruction = new CancelInstruction(StrategyTicker.SOXL, "toss-oid-500");
        doThrow(new TossApiException("Toss API 요청 실패: 500", new RuntimeException("boom")))
            .when(tossHttpClient).post(anyString(), any(), any(), eq(Void.class));

        assertThatThrownBy(() -> tossOrderApi.cancel(instruction, ACCOUNT))
            .isInstanceOf(TossApiException.class);
    }

    @Test
    @DisplayName("취소 실패(404)도 이미 체결/만료로 추정하지 않고 그대로 전파된다")
    void cancel_notFound_rethrows() {
        CancelInstruction instruction = new CancelInstruction(StrategyTicker.SOXL, "toss-oid-404");
        doThrow(new TossApiException("Toss API 오류: 404 NOT_FOUND", new RuntimeException("not found")))
            .when(tossHttpClient).post(anyString(), any(), any(), eq(Void.class));

        assertThatThrownBy(() -> tossOrderApi.cancel(instruction, ACCOUNT))
            .isInstanceOf(TossApiException.class);
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
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), StrategyTicker.SOXL, ACCOUNT);

        assertThat(executions).hasSize(1);
        Execution e = executions.get(0);
        assertThat(e.quantity()).isEqualTo(3);
        assertThat(e.price()).isEqualByComparingTo("25.50");
        assertThat(e.amountUsd()).isEqualByComparingTo("76.50");
        assertThat(e.direction()).isEqualTo(Direction.BUY);
        assertThat(e.externalOrderId()).isEqualTo("oid-1");
        assertThat(e.ticker()).isEqualTo(StrategyTicker.SOXL);
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
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), StrategyTicker.SOXL, ACCOUNT);

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
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), StrategyTicker.SOXL, ACCOUNT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).direction()).isEqualTo(Direction.SELL);
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
            LocalDate.of(2026, 6, 17), LocalDate.of(2026, 6, 17), StrategyTicker.SOXL, ACCOUNT);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).amountUsd()).isEqualByComparingTo("50.00");
    }

    // --- helpers ---

    private OrderInstruction locBuyInstruction() {
        return new OrderInstruction(StrategyTicker.SOXL, Direction.BUY, OrderType.LOC, 2, new BigDecimal("25.50"));
    }

    private OrderInstruction mocSellInstruction() {
        return new OrderInstruction(StrategyTicker.SOXL, Direction.SELL, OrderType.MOC, 1, BigDecimal.ZERO);
    }

    private OrderInstruction limitBuyInstruction() {
        return new OrderInstruction(StrategyTicker.SOXL, Direction.BUY, OrderType.LIMIT, 1, new BigDecimal("25.00"));
    }
}
