package com.kista.trading.stats.adapter.in.web;

import com.kista.trading.stats.domain.model.*;
import com.kista.trading.stats.application.usecase.TradingStatsUseCase;
import com.kista.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

@WebMvcTest(TradingStatsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class TradingStatsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean TradingStatsUseCase tradingStats;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void summary를_반환한다() throws Exception {
        when(tradingStats.getSummary(USER_ID)).thenReturn(new StatsSummary(
                new BigDecimal("50.00"), new BigDecimal("10.00"), new BigDecimal("1000.00"),
                List.of(new StrategyTypeStats(StrategyType.INFINITE, 2, 1,
                        new BigDecimal("0.5000"), new BigDecimal("0.0250"), new BigDecimal("20.0"),
                        new BigDecimal("50.00"), new BigDecimal("10.00")))));

        mockMvc.perform(get("/api/stats/summary").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRealizedPnl").value(50.00))
                .andExpect(jsonPath("$.byType[0].type").value("INFINITE"))
                .andExpect(jsonPath("$.byType[0].winRate").value(0.5));
    }

    @Test
    void equity_curve를_반환한다() throws Exception {
        when(tradingStats.getEquityCurve(eq(USER_ID), isNull(), any(), any()))
                .thenReturn(new EquityCurve(
                        List.of(new EquityPoint(LocalDate.parse("2026-06-02"),
                                new BigDecimal("1000.00"), new BigDecimal("900.00")))));

        mockMvc.perform(get("/api/stats/equity-curve")
                        .param("from", "2026-06-01").param("to", "2026-06-30")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].date").value("2026-06-02"))
                .andExpect(jsonPath("$.points[0].totalAsset").value(1000.00))
                .andExpect(jsonPath("$.benchmark").doesNotExist());
    }

    @Test
    void equity_curve는_type_필터를_전달한다() throws Exception {
        when(tradingStats.getEquityCurve(eq(USER_ID), eq(StrategyType.VR), any(), any()))
                .thenReturn(new EquityCurve(List.of()));

        mockMvc.perform(get("/api/stats/equity-curve")
                        .param("type", "VR")
                        .param("from", "2026-06-01")
                        .param("to", "2026-06-30")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk());

        verify(tradingStats).getEquityCurve(
                USER_ID, StrategyType.VR,
                LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-30"));
    }

    @Test
    void cycles를_커서와_함께_반환한다() throws Exception {
        var createdAt = Instant.parse("2026-02-01T00:00:00Z");
        when(tradingStats.getCyclePerformances(eq(USER_ID), isNull(), isNull(), eq(50)))
                .thenReturn(new CyclePerformancePage(
                        List.of(new CyclePerformance(UUID.randomUUID(), UUID.randomUUID(), StrategyType.INFINITE,
                                StrategyTicker.SOXL, LocalDate.parse("2026-01-01"),
                                LocalDate.parse("2026-01-31"), new BigDecimal("1000.00"),
                                new BigDecimal("1100.00"), new BigDecimal("100.00"),
                                new BigDecimal("0.1000"), 30, true, createdAt)),
                        createdAt, true));

        mockMvc.perform(get("/api/stats/cycles").with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].closed").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }
}
