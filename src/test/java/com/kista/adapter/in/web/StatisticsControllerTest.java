package com.kista.adapter.in.web;

import com.kista.domain.model.kis.KisApiException;
import com.kista.domain.model.broker.PresentBalanceResult;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.AccountStatisticsUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(StatisticsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class StatisticsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AccountStatisticsUseCase accountStatistics;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    @Test
    void portfolio_returns_200_with_result() throws Exception {
        PresentBalanceResult result = new PresentBalanceResult(
                List.of(), new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("5.0"),
                BigDecimal.ZERO, BigDecimal.ZERO
        );
        when(accountStatistics.getPresentBalance(eq(ACCOUNT_ID), any()))
                .thenReturn(result);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/portfolio")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalAssetUsd").value(1000.00));
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
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prices[0].ticker").value("TQQQ"))
                .andExpect(jsonPath("$.prices[0].price").value(120.50))
                .andExpect(jsonPath("$.prices[1].ticker").value("SOXL"))
                .andExpect(jsonPath("$.prices[1].price").value(35.20));
    }

    @Test
    void prices_returns_400_when_tickers_empty() throws Exception {
        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/prices")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void prices_returns_403_when_not_owner() throws Exception {
        when(accountStatistics.getPrices(any(), any(), any()))
                .thenThrow(new SecurityException("접근 불가"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/prices")
                        .param("tickers", "TQQQ")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void prices_returns_503_on_kis_error() throws Exception {
        when(accountStatistics.getPrices(any(), any(), any()))
                .thenThrow(new KisApiException("KIS API 오류", null));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/prices")
                        .param("tickers", "TQQQ")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isServiceUnavailable());
    }

}
