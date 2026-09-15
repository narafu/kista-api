package com.kista.marketcalendar.adapter.in.web;

import com.kista.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import com.kista.trading.TradingApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// root market 모듈이 소비하는 내부 전용 캘린더 엔드포인트 — 각 라우트가 MarketCalendarPort로
// 올바르게 위임하는지만 검증(얇은 pass-through라 예외 시나리오 없음)
@WebMvcTest(MarketCalendarInternalController.class)
@ContextConfiguration(classes = TradingApplication.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@TestPropertySource(properties = "internal.api.token=test-internal-token")
@Execution(ExecutionMode.SAME_THREAD)
class MarketCalendarInternalControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean MarketCalendarPort marketCalendarPort;

    private static final String VALID_TOKEN = "test-internal-token";

    @Test
    void holidays_포트에_그대로_위임한다() throws Exception {
        given(marketCalendarPort.findHolidaysForMonth(2026, 1))
                .willReturn(List.of(LocalDate.of(2026, 1, 1)));

        mockMvc.perform(get("/api/internal/marketcalendar/holidays")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("year", "2026")
                        .param("month", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("2026-01-01"));

        verify(marketCalendarPort).findHolidaysForMonth(2026, 1);
    }

    @Test
    void is_open_포트에_그대로_위임한다() throws Exception {
        given(marketCalendarPort.isMarketOpen(LocalDate.of(2026, 1, 2))).willReturn(true);

        mockMvc.perform(get("/api/internal/marketcalendar/is-open")
                        .header("X-Internal-Token", VALID_TOKEN)
                        .param("date", "2026-01-02"))
                .andExpect(status().isOk());

        verify(marketCalendarPort).isMarketOpen(LocalDate.of(2026, 1, 2));
    }

    @Test
    void session_현재_세션을_반환한다() throws Exception {
        mockMvc.perform(get("/api/internal/marketcalendar/session")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").exists())
                .andExpect(jsonPath("$.isDst").exists());
    }

    @Test
    void token_없으면_401() throws Exception {
        mockMvc.perform(get("/api/internal/marketcalendar/holidays")
                        .param("year", "2026")
                        .param("month", "1"))
                .andExpect(status().isUnauthorized());
    }
}
