package com.kista.adapter.in.web;

import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.*;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.TossStatisticsUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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

@WebMvcTest(TossStatisticsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class TossStatisticsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 의존성
    @MockitoBean TossStatisticsUseCase tossStatistics;

    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000099");

    // ─── GET /candles ────────────────────────────────────────────────────────────

    @Test
    void candles_returns_200_with_list() throws Exception {
        TossCandle candle = new TossCandle(
                LocalDate.of(2025, 1, 2),
                new BigDecimal("25.10"), new BigDecimal("26.50"),
                new BigDecimal("24.80"), new BigDecimal("26.00"),
                1_000_000L
        );
        when(tossStatistics.getCandles(eq(ACCOUNT_ID), any(), eq(Ticker.SOXL), eq("1D"), any(), any()))
                .thenReturn(List.of(candle));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/candles")
                        .param("ticker", "SOXL")
                        .param("interval", "1D")
                        .param("from", "2025-01-01")
                        .param("to", "2025-01-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].close").value(26.00))
                .andExpect(jsonPath("$[0].volume").value(1_000_000));
    }

    @Test
    void candles_returns_403_when_not_owner() throws Exception {
        when(tossStatistics.getCandles(any(), any(), any(), any(), any(), any()))
                .thenThrow(new SecurityException("접근 불가"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/candles")
                        .param("ticker", "SOXL")
                        .param("interval", "1D")
                        .param("from", "2025-01-01")
                        .param("to", "2025-01-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void candles_returns_400_when_not_toss_account() throws Exception {
        when(tossStatistics.getCandles(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Toss 계좌에서만 사용할 수 있습니다"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/candles")
                        .param("ticker", "SOXL")
                        .param("interval", "1D")
                        .param("from", "2025-01-01")
                        .param("to", "2025-01-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void candles_returns_503_on_toss_api_error() throws Exception {
        when(tossStatistics.getCandles(any(), any(), any(), any(), any(), any()))
                .thenThrow(new TossApiException("Toss API 오류", null));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/candles")
                        .param("ticker", "SOXL")
                        .param("interval", "1D")
                        .param("from", "2025-01-01")
                        .param("to", "2025-01-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isServiceUnavailable());
    }

    // ─── GET /stock-info ─────────────────────────────────────────────────────────

    @Test
    void stockInfo_returns_200_with_fields() throws Exception {
        TossStockInfo info = new TossStockInfo("SOXL", "디렉시온 반도체", "Direxion Semi", "NYSE ARCA", "USD", "NORMAL");
        when(tossStatistics.getStockInfo(eq(ACCOUNT_ID), any(), eq(Ticker.SOXL)))
                .thenReturn(info);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/stock-info")
                        .param("ticker", "SOXL")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("SOXL"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("NORMAL"));
    }

    @Test
    void stockInfo_returns_503_on_toss_api_error() throws Exception {
        when(tossStatistics.getStockInfo(any(), any(), any()))
                .thenThrow(new TossApiException("Toss API 오류", null));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/stock-info")
                        .param("ticker", "SOXL")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isServiceUnavailable());
    }

    // ─── GET /exchange-rate ──────────────────────────────────────────────────────

    @Test
    void exchangeRate_returns_200_with_rates() throws Exception {
        TossExchangeRate rate = new TossExchangeRate(new BigDecimal("1380.50"), new BigDecimal("1375.00"));
        when(tossStatistics.getExchangeRate(eq(ACCOUNT_ID), any()))
                .thenReturn(rate);

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/exchange-rate")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rate").value(1380.50))
                .andExpect(jsonPath("$.midRate").value(1375.00));
    }

    @Test
    void exchangeRate_returns_400_when_not_toss_account() throws Exception {
        when(tossStatistics.getExchangeRate(any(), any()))
                .thenThrow(new IllegalStateException("Toss 계좌에서만 사용할 수 있습니다"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/exchange-rate")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isBadRequest());
    }

    // ─── GET /market-calendar ────────────────────────────────────────────────────

    @Test
    void marketCalendar_returns_200_with_sessions() throws Exception {
        OffsetDateTime start = OffsetDateTime.parse("2025-01-02T14:30:00Z");
        OffsetDateTime end   = OffsetDateTime.parse("2025-01-02T21:00:00Z");
        TossMarketSession session = new TossMarketSession(
                LocalDate.of(2025, 1, 2),
                new TossMarketSession.SessionHours(start.minusHours(4), start.minusHours(4).plusHours(4)),
                new TossMarketSession.SessionHours(start, end),
                new TossMarketSession.SessionHours(end, end.plusHours(4))
        );
        when(tossStatistics.getMarketCalendar(eq(ACCOUNT_ID), any(), any(), any()))
                .thenReturn(List.of(session));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/market-calendar")
                        .param("from", "2025-01-01")
                        .param("to", "2025-01-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].date").value("2025-01-02"))
                .andExpect(jsonPath("$[0].isOpen").value(true));
    }

    @Test
    void marketCalendar_returns_503_on_toss_api_error() throws Exception {
        when(tossStatistics.getMarketCalendar(any(), any(), any(), any()))
                .thenThrow(new TossApiException("Toss API 오류", null));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/market-calendar")
                        .param("from", "2025-01-01")
                        .param("to", "2025-01-31")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isServiceUnavailable());
    }

    // ─── GET /broker-accounts ────────────────────────────────────────────────────

    @Test
    void brokerAccounts_returns_200_with_list() throws Exception {
        TossAccountInfo account = new TossAccountInfo(1, "123-456-7890");
        when(tossStatistics.getAccountList(eq(ACCOUNT_ID), any()))
                .thenReturn(List.of(account));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/broker-accounts")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].accountSeq").value(1))
                .andExpect(jsonPath("$[0].accountNo").value("123-456-7890"));
    }

    @Test
    void brokerAccounts_returns_403_when_not_owner() throws Exception {
        when(tossStatistics.getAccountList(any(), any()))
                .thenThrow(new SecurityException("접근 불가"));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/broker-accounts")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void brokerAccounts_returns_503_on_toss_api_error() throws Exception {
        when(tossStatistics.getAccountList(any(), any()))
                .thenThrow(new TossApiException("Toss API 오류", null));

        mockMvc.perform(get("/api/accounts/" + ACCOUNT_ID + "/broker-accounts")
                        .with(authentication(userToken(DEV_USER_UUID))))
                .andExpect(status().isServiceUnavailable());
    }
}
