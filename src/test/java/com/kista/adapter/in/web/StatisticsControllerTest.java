package com.kista.adapter.in.web;

import com.kista.domain.model.tradingcycle.CycleHistoryPage;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.kis.*;
import com.kista.domain.port.in.AccountStatisticsUseCase;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(KisStatisticsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class StatisticsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean AccountStatisticsUseCase accountStatistics;

    private static final String USER_ID = "00000000-0000-0000-0000-000000000001";
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    private UsernamePasswordAuthenticationToken mockAuth() {
        return new UsernamePasswordAuthenticationToken(UUID.fromString(USER_ID), null, List.of());
    }

    @Test
    void profit_returns_200_with_result() throws Exception {
        PeriodProfitResult result = new PeriodProfitResult(
                List.of(new PeriodProfitResult.Item("20240615", Ticker.SOXL, 5,
                        new BigDecimal("20.00"), new BigDecimal("25.00"),
                        new BigDecimal("25.00"), new BigDecimal("25.0"), "NASD")),
                new BigDecimal("125.00"), new BigDecimal("25.0")
        );
        when(accountStatistics.getPeriodProfit(eq(ACCOUNT_ID), any(), any(), any()))
                .thenReturn(result);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/profit")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRealizedProfit").value(125.00))
                .andExpect(jsonPath("$.items[0].ticker").value("SOXL"));
    }

    @Test
    void trades_returns_200_with_list() throws Exception {
        when(accountStatistics.getExecutions(eq(ACCOUNT_ID), any(), any(), any()))
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
        when(accountStatistics.getPresentBalance(eq(ACCOUNT_ID), any()))
                .thenReturn(result);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/portfolio")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalAssetUsd").value(1000.00));
    }

    @Test
    void profit_returns_403_when_not_owner() throws Exception {
        when(accountStatistics.getPeriodProfit(any(), any(), any(), any()))
                .thenThrow(new SecurityException("접근 불가"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/profit")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void profit_returns_404_when_account_not_found() throws Exception {
        when(accountStatistics.getPeriodProfit(any(), any(), any(), any()))
                .thenThrow(new NoSuchElementException("없음"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/profit")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    void profit_returns_503_on_kis_error() throws Exception {
        // KIS API 실패는 KisApiException → GlobalExceptionHandler에서 503 변환
        when(accountStatistics.getPeriodProfit(any(), any(), any(), any()))
                .thenThrow(new KisApiException("KIS API 오류", null));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/profit")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void prices_returns_200_with_ticker_price_list() throws Exception {
        Map<Ticker, BigDecimal> priceMap = new LinkedHashMap<>();
        priceMap.put(Ticker.TQQQ, new BigDecimal("120.50"));
        priceMap.put(Ticker.SOXL, new BigDecimal("35.20"));
        when(accountStatistics.getPrices(eq(ACCOUNT_ID), any(), any()))
                .thenReturn(priceMap);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/prices")
                        .param("tickers", "TQQQ", "SOXL")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices[0].ticker").value("TQQQ"))
                .andExpect(jsonPath("$.prices[0].price").value(120.50))
                .andExpect(jsonPath("$.prices[1].ticker").value("SOXL"))
                .andExpect(jsonPath("$.prices[1].price").value(35.20));
    }

    @Test
    void prices_returns_400_when_tickers_empty() throws Exception {
        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/prices")
                        .with(authentication(mockAuth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void prices_returns_403_when_not_owner() throws Exception {
        when(accountStatistics.getPrices(any(), any(), any()))
                .thenThrow(new SecurityException("접근 불가"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/prices")
                        .param("tickers", "TQQQ")
                        .with(authentication(mockAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    void prices_returns_503_on_kis_error() throws Exception {
        when(accountStatistics.getPrices(any(), any(), any()))
                .thenThrow(new KisApiException("KIS API 오류", null));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/prices")
                        .param("tickers", "TQQQ")
                        .with(authentication(mockAuth())))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void cycleHistory_returns_page_with_date_params() throws Exception {
        var page = new CycleHistoryPage(List.of(), null, false);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), any(), any(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void cycleHistory_returns_page_without_date_params() throws Exception {
        // '전체' 선택 시 from/to 없어도 200 반환
        var page = new CycleHistoryPage(List.of(), null, false);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), isNull(), isNull(), isNull(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void cycleHistory_returns_nextCursor_when_hasMore() throws Exception {
        Instant cursor = Instant.parse("2024-06-01T00:00:00Z");
        var page = new CycleHistoryPage(List.of(), cursor, true);
        when(accountStatistics.getByAccount(eq(ACCOUNT_ID), any(), any(), any(), any(), eq(50)))
                .thenReturn(page);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/cycle-history")
                        .param("from", "2024-01-01").param("to", "2024-12-31")
                        .with(authentication(mockAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2024-06-01T00:00:00Z"));
    }
}
