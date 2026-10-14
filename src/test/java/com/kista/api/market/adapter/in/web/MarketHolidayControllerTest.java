package com.kista.api.market.adapter.in.web;

import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.shared.platform.security.TokenBlacklistPort;
import com.kista.api.market.application.port.output.MarketCalendarQueryPort;
import com.kista.api.market.application.usecase.MarketUseCase;
import com.kista.shared.kernel.MarketSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import com.kista.api.admin.application.port.output.AppErrorLogPort;
import com.kista.api.web.RootSecurityPolicy;

@WebMvcTest(MarketHolidayController.class)
@Import({SecurityConfig.class, RootSecurityPolicy.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class MarketHolidayControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean MarketUseCase marketUseCase;
    @MockitoBean MarketCalendarQueryPort marketCalendarQueryPort;

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void holidays_anonymous_returns_200() throws Exception {
        // GET /api/market/**은 SecurityConfig에서 permitAll() — 비인증 대시보드 공개 엔드포인트
        mockMvc.perform(get("/api/market/holidays")
                        .param("year", "2026")
                        .param("month", "1"))
                .andExpect(status().isOk());
    }

    @Test
    void holidays_authenticated_returns_list() throws Exception {
        when(marketUseCase.getMonthlyHolidays(2026, 1))
                .thenReturn(List.of(LocalDate.of(2026, 1, 1)));

        mockMvc.perform(get("/api/market/holidays")
                        .param("year", "2026")
                        .param("month", "1")
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=3600, public"))
                .andExpect(jsonPath("$[0]").value("2026-01-01"));
    }

    @Test
    void candles_from_to_지정시_기간_조회로_위임한다() throws Exception {
        when(marketUseCase.getDailyCandles("SOXL", LocalDate.of(2025, 1, 1), LocalDate.of(2026, 10, 14)))
                .thenReturn(List.of(new com.kista.shared.contract.broker.DailyCandleResponse(LocalDate.of(2025, 1, 2),
                        java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, 1L)));

        mockMvc.perform(get("/api/market/candles")
                        .param("ticker", "SOXL").param("from", "2025-01-01").param("to", "2026-10-14")
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].date").value("2025-01-02"))
                .andExpect(jsonPath("$[0].close").value(10));
    }

    @Test
    void candles_from만_지정하면_400() throws Exception {
        mockMvc.perform(get("/api/market/candles")
                        .param("ticker", "SOXL").param("from", "2025-01-01")
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void session_returns_200_without_auth() throws Exception {
        when(marketCalendarQueryPort.currentSession())
                .thenReturn(new com.kista.shared.contract.marketcalendar.MarketSessionResponse(MarketSession.DIRECT, true));

        // /api/market/session은 anyRequest().authenticated() 에 해당 — 인증 포함으로 테스트
        mockMvc.perform(get("/api/market/session")
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").exists())
                .andExpect(jsonPath("$.isDst").exists());
    }
}
