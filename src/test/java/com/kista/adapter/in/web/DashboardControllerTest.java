package com.kista.adapter.in.web;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.AccountCycleHistoryEntry;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.GetPortfolioUseCase;
import com.kista.domain.port.in.GetTradeHistoryUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardController.class)
@Execution(ExecutionMode.SAME_THREAD)
@WithMockUser // Spring Security 적용 후 인증된 사용자로 테스트
class DashboardControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockBean GetTradeHistoryUseCase getTradeHistoryUseCase;
    @MockBean GetPortfolioUseCase getPortfolioUseCase;

    @Test
    void getTrades_returns_200_with_list() throws Exception {
        Order o = new Order(UUID.randomUUID(), UUID.randomUUID(), LocalDate.now(), Ticker.SOXL,
                Order.OrderType.LOC, Order.OrderDirection.BUY, 10,
                new BigDecimal("25.00"), Order.OrderStatus.PLACED, "KIS001");
        when(getTradeHistoryUseCase.getHistory(any(), any(), any())).thenReturn(List.of(o));

        mockMvc.perform(get("/api/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("SOXL"))
                .andExpect(jsonPath("$[0].quantity").value(10));
    }

    @Test
    void getPortfolioCurrent_returns_200() throws Exception {
        AccountCycleHistoryEntry snap = new AccountCycleHistoryEntry(
                UUID.randomUUID(), Ticker.SOXL,
                new BigDecimal("1000.00"), new BigDecimal("26.00"),
                new BigDecimal("25.0000"), 100, Instant.now());
        when(getPortfolioUseCase.getCurrent()).thenReturn(snap);

        mockMvc.perform(get("/api/portfolio/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("SOXL"))
                .andExpect(jsonPath("$.holdings").value(100));
    }

    @Test
    void getPortfolioSnapshots_returns_200() throws Exception {
        when(getPortfolioUseCase.getSnapshots(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/portfolio/snapshots?from=2026-01-01&to=2026-01-31"))
                .andExpect(status().isOk());
    }

}
