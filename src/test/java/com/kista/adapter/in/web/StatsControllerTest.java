package com.kista.adapter.in.web;

import com.kista.domain.model.stats.*;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.UserStatsUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StatsController.class)
@Execution(ExecutionMode.SAME_THREAD)
class StatsControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort; // GlobalExceptionHandler 의존성
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성 — JwtDecoderConfig bean 실제 파싱 방지
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean UserStatsUseCase userStats;

    private static final UUID USER_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    void summary를_반환한다() throws Exception {
        when(userStats.getSummary(USER_ID)).thenReturn(new StatsSummary(
                new BigDecimal("50.00"), new BigDecimal("10.00"), new BigDecimal("1000.00"),
                List.of(new StrategyTypeStats(Strategy.Type.INFINITE, 2, 1,
                        new BigDecimal("0.5000"), new BigDecimal("0.0250"), new BigDecimal("20.0"),
                        new BigDecimal("50.00"), new BigDecimal("10.00")))));

        mockMvc.perform(get("/api/stats/summary").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRealizedPnl").value(50.00))
                .andExpect(jsonPath("$.byType[0].type").value("INFINITE"))
                .andExpect(jsonPath("$.byType[0].winRate").value(0.5));
    }

    @Test
    void equity_curve를_반환한다() throws Exception {
        when(userStats.getEquityCurve(eq(USER_ID), any(), any(), eq("QLD")))
                .thenReturn(new EquityCurve(
                        List.of(new EquityPoint(LocalDate.parse("2026-06-02"),
                                new BigDecimal("1000.00"), new BigDecimal("900.00"))),
                        List.of(new IndexPrice("QLD", LocalDate.parse("2026-06-02"),
                                new BigDecimal("450.00")))));

        mockMvc.perform(get("/api/stats/equity-curve")
                        .param("from", "2026-06-01").param("to", "2026-06-30")
                        .param("benchmark", "QLD")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].date").value("2026-06-02"))
                .andExpect(jsonPath("$.points[0].totalAsset").value(1000.00))
                .andExpect(jsonPath("$.benchmark[0].close").value(450.00));
    }

    @Test
    void 잘못된_벤치마크_심볼은_400을_반환한다() throws Exception {
        when(userStats.getEquityCurve(eq(USER_ID), any(), any(), eq("TSLA")))
                .thenThrow(new IllegalArgumentException("지원하지 않는 벤치마크 심볼"));

        mockMvc.perform(get("/api/stats/equity-curve").param("benchmark", "TSLA")
                        .with(authentication(auth())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cycles를_커서와_함께_반환한다() throws Exception {
        var createdAt = java.time.Instant.parse("2026-02-01T00:00:00Z");
        when(userStats.getCyclePerformances(eq(USER_ID), isNull(), isNull(), eq(50)))
                .thenReturn(new CyclePerformancePage(
                        List.of(new CyclePerformance(UUID.randomUUID(), Strategy.Type.INFINITE,
                                Strategy.Ticker.SOXL, LocalDate.parse("2026-01-01"),
                                LocalDate.parse("2026-01-31"), new BigDecimal("1000.00"),
                                new BigDecimal("1100.00"), new BigDecimal("100.00"),
                                new BigDecimal("0.1000"), 30, true, createdAt)),
                        createdAt, true));

        mockMvc.perform(get("/api/stats/cycles").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].closed").value(true))
                .andExpect(jsonPath("$.nextCursor").value("2026-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }
}
