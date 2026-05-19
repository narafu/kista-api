package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.adapter.in.web.dto.ReservationOrderRequest;
import com.kista.domain.model.*;
import com.kista.domain.port.in.GetNextOrdersUseCase;
import com.kista.domain.port.in.PlaceReservationOrderUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@Execution(ExecutionMode.SAME_THREAD)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean PlaceReservationOrderUseCase placeReservationOrderUseCase;
    @MockBean GetNextOrdersUseCase getNextOrders;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    private GetNextOrdersUseCase.Result buildNextResult() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        InfinitePosition position = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("22.00"));
        Order order = new Order(LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, new BigDecimal("20.00"), Order.OrderStatus.PLACED, null);
        return new GetNextOrdersUseCase.Result(LocalDate.now(), position, List.of(order));
    }

    // --- /orders/next (다음 주문 미리보기) ---

    @Test
    void next_returns_200_with_orders_and_position() throws Exception {
        when(getNextOrders.preview(eq(ACCOUNT_ID), any())).thenReturn(buildNextResult());

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/orders/next")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.position.symbol").value("SOXL"))
                .andExpect(jsonPath("$.position.currentPrice").value(22.00))
                .andExpect(jsonPath("$.orders[0].direction").value("BUY"))
                .andExpect(jsonPath("$.orders[0].orderType").value("LOC"));
    }

    @Test
    void next_returns_403_when_not_owner() throws Exception {
        when(getNextOrders.preview(any(), any())).thenThrow(new SecurityException("접근 불가"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/orders/next")
                        .with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void next_returns_404_when_account_not_found() throws Exception {
        when(getNextOrders.preview(any(), any())).thenThrow(new NoSuchElementException("계좌 없음"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/orders/next")
                        .with(authentication(mockAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void next_returns_503_on_kis_error() throws Exception {
        when(getNextOrders.preview(any(), any())).thenThrow(new RuntimeException("KIS API 오류"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/orders/next")
                        .with(authentication(mockAuth())))
                .andExpect(status().isServiceUnavailable());
    }

    // --- /reservation-orders (예약주문 접수) ---

    @Test
    void reservationOrder_returns_201_on_success() throws Exception {
        ReservationOrderReceipt receipt = new ReservationOrderReceipt("ORD-001", "RSV-001", "20260519");
        when(placeReservationOrderUseCase.place(eq(ACCOUNT_ID), any(), any())).thenReturn(receipt);

        ReservationOrderRequest request = new ReservationOrderRequest(
                Ticker.SOXL, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"));

        mockMvc.perform(post("/api/accounts/" + ACCOUNT_ID + "/reservation-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(authentication(mockAuth()))
                        .with(csrf()))
                .andExpect(status().isCreated());
    }

    @Test
    void reservationOrder_returns_403_when_not_owner() throws Exception {
        when(placeReservationOrderUseCase.place(any(), any(), any()))
                .thenThrow(new SecurityException("접근 불가"));

        ReservationOrderRequest request = new ReservationOrderRequest(
                Ticker.SOXL, Order.OrderDirection.BUY, 1, new BigDecimal("20.00"));

        mockMvc.perform(post("/api/accounts/" + ACCOUNT_ID + "/reservation-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(authentication(mockAuth()))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }
}
