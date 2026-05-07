package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.adapter.in.web.dto.FidaOrderRequestDto;
import com.kista.domain.model.Order;
import com.kista.domain.model.PortfolioSnapshot;
import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.in.ExecuteFidaOrderUseCase;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Execution(ExecutionMode.SAME_THREAD)
@WithMockUser // Spring Security 적용 후 인증된 사용자로 테스트
class DashboardControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean GetTradeHistoryUseCase getTradeHistoryUseCase;
    @MockBean GetPortfolioUseCase getPortfolioUseCase;
    @MockBean ExecuteFidaOrderUseCase executeFidaOrderUseCase;

    @Test
    void getTrades_returns_200_with_list() throws Exception {
        TradeHistory h = new TradeHistory(UUID.randomUUID(), LocalDate.now(), "SOXL", "SOXL_DIVISION",
                Order.OrderType.LOC, Order.OrderDirection.BUY, 10,
                new BigDecimal("25.00"), new BigDecimal("250.00"),
                Order.OrderStatus.PLACED, "KIS001", null, Instant.now());
        when(getTradeHistoryUseCase.getHistory(any(), any(), any())).thenReturn(List.of(h));

        mockMvc.perform(get("/api/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("SOXL"))
                .andExpect(jsonPath("$[0].qty").value(10));
    }

    @Test
    void getPortfolioCurrent_returns_200() throws Exception {
        PortfolioSnapshot snap = new PortfolioSnapshot(UUID.randomUUID(), LocalDate.now(), "SOXL",
                100, new BigDecimal("25.0000"), new BigDecimal("26.0000"),
                new BigDecimal("2600.00"), new BigDecimal("1000.00"),
                new BigDecimal("3600.00"), null, Instant.now());
        when(getPortfolioUseCase.getCurrent()).thenReturn(snap);

        mockMvc.perform(get("/api/portfolio/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("SOXL"))
                .andExpect(jsonPath("$.qty").value(100));
    }

    @Test
    void getPortfolioSnapshots_returns_200() throws Exception {
        when(getPortfolioUseCase.getSnapshots(anyInt())).thenReturn(List.of());

        mockMvc.perform(get("/api/portfolio/snapshots?days=7"))
                .andExpect(status().isOk());
    }

    @Test
    void placeFidaOrder_returns_201() throws Exception {
        FidaOrderRequestDto dto = new FidaOrderRequestDto(
                "SOXL", Order.OrderDirection.BUY, 5, new BigDecimal("25.50"));

        mockMvc.perform(post("/api/orders/fida")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());
    }

    @Test
    void placeFidaOrder_invalid_body_returns_400() throws Exception {
        mockMvc.perform(post("/api/orders/fida")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
