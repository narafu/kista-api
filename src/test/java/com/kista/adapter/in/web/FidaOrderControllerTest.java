package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.PrivacyUseCase;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.privacy.PrivacyTradeSaveResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FidaOrderController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class FidaOrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean PrivacyUseCase privacy;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void placeFidaOrder_returns_201_with_body() throws Exception {
        UUID masterId = UUID.randomUUID();
        FidaOrderCommand req = new FidaOrderCommand(
                LocalDate.now(), Ticker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 10, List.of());

        given(privacy.executeFidaOrder(any())).willReturn(new PrivacyTradeSaveResult(masterId, true));

        mockMvc.perform(post("/api/internal/fida-orders")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(masterId.toString()))
                .andExpect(jsonPath("$.ticker").value("SOXL"))
                .andExpect(jsonPath("$.holdings").value(10));
    }

    @Test
    void placeFidaOrder_invalid_body_returns_400() throws Exception {
        mockMvc.perform(post("/api/internal/fida-orders")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void placeFidaOrder_buy_with_null_quantity_returns_400() throws Exception {
        // BUY 주문에 quantity=null — "남은 전부"는 SELL 전용
        Order buyNullQty = new Order(null, null, LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LIMIT, Order.OrderDirection.BUY, null,
                new BigDecimal("22.00"), Order.OrderStatus.PLANNED, null);
        FidaOrderCommand req = new FidaOrderCommand(
                LocalDate.now(), Ticker.SOXL, new BigDecimal("500.00"),
                BigDecimal.ZERO, new BigDecimal("25.50"), 10, List.of(buyNullQty));

        mockMvc.perform(post("/api/internal/fida-orders")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void placeFidaOrder_unauthenticated_returns_401() throws Exception {
        mockMvc.perform(post("/api/internal/fida-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
