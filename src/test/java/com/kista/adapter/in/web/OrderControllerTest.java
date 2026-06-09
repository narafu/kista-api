package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.domain.model.strategy.*;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.order.*;
import com.kista.domain.model.order.NextOrdersPreview;
import com.kista.domain.port.in.TradingExecutionUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)
@Execution(ExecutionMode.SAME_THREAD)
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TradingExecutionUseCase tradingExecution;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    private NextOrdersPreview buildNextResult() {
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20.00"), new BigDecimal("1000.00"));
        InfinitePosition position = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("21.00"));
        Order order = new Order(null, null, LocalDate.now(), Ticker.SOXL, Order.OrderType.LOC,
                Order.OrderDirection.BUY, 1, new BigDecimal("20.00"), Order.OrderStatus.PLACED, null, null, null);
        return new NextOrdersPreview(LocalDate.now(), position, List.of(order), null);
    }

    @Test
    void next_returns_200_with_orders_and_position() throws Exception {
        when(tradingExecution.preview(eq(ACCOUNT_ID), any())).thenReturn(buildNextResult());

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/orders/preview")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.position.ticker").value("SOXL"))
                .andExpect(jsonPath("$.orders[0].direction").value("BUY"))
                .andExpect(jsonPath("$.orders[0].orderType").value("LOC"))
                .andExpect(jsonPath("$.skipReason").doesNotExist());
    }

    @Test
    void next_returns_403_when_not_owner() throws Exception {
        when(tradingExecution.preview(any(), any())).thenThrow(new SecurityException("접근 불가"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/orders/preview")
                        .with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void next_returns_404_when_account_not_found() throws Exception {
        when(tradingExecution.preview(any(), any())).thenThrow(new NoSuchElementException("계좌 없음"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/orders/preview")
                        .with(authentication(mockAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void next_returns_503_on_kis_error() throws Exception {
        when(tradingExecution.preview(any(), any())).thenThrow(new KisApiException("KIS API 오류", null));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/orders/preview")
                        .with(authentication(mockAuth())))
                .andExpect(status().isServiceUnavailable());
    }
}
