package com.kista.adapter.in.web;

import com.kista.domain.model.*;
import com.kista.domain.port.in.GetAccountStatisticsUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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

@WebMvcTest(StatisticsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class StatisticsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockBean GetAccountStatisticsUseCase statisticsUseCase;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    @Test
    void profit_returns_200_with_result() throws Exception {
        PeriodProfitResult result = new PeriodProfitResult(
                List.of(new PeriodProfitResult.Item("20240615", "SOXL", 5,
                        new BigDecimal("20.00"), new BigDecimal("25.00"),
                        new BigDecimal("25.00"), new BigDecimal("25.0"), "NASD")),
                new BigDecimal("125.00"), new BigDecimal("25.0")
        );
        when(statisticsUseCase.getPeriodProfit(eq(ACCOUNT_ID), any(), any(), any()))
                .thenReturn(result);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/profit")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRealizedProfit").value(125.00))
                .andExpect(jsonPath("$.items[0].symbol").value("SOXL"));
    }

    @Test
    void trades_returns_200_with_list() throws Exception {
        when(statisticsUseCase.getTrades(eq(ACCOUNT_ID), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/trades")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void portfolio_returns_200_with_result() throws Exception {
        PresentBalanceResult result = new PresentBalanceResult(
                List.of(), new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("5.0")
        );
        when(statisticsUseCase.getPresentBalance(eq(ACCOUNT_ID), any()))
                .thenReturn(result);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/portfolio")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAssetUsd").value(1000.00));
    }

    @Test
    void profit_returns_403_when_not_owner() throws Exception {
        when(statisticsUseCase.getPeriodProfit(any(), any(), any(), any()))
                .thenThrow(new SecurityException("접근 불가"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/profit")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void profit_returns_404_when_account_not_found() throws Exception {
        when(statisticsUseCase.getPeriodProfit(any(), any(), any(), any()))
                .thenThrow(new NoSuchElementException("없음"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/profit")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void profit_returns_503_on_kis_error() throws Exception {
        // KIS API 실패는 RuntimeException으로 전파됨 → 컨트롤러에서 503으로 변환
        when(statisticsUseCase.getPeriodProfit(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("KIS API 오류"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/profit")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isServiceUnavailable());
    }
}
