package com.kista.tradingcore.marketcalendar.adapter.in.web;

import com.kista.shared.support.FixedClockTestConfig;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import com.kista.shared.platform.security.InternalTokenAuthFilter;
import com.kista.shared.platform.security.JwtAuthFilter;
import com.kista.shared.platform.security.SecurityConfig;
import com.kista.shared.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// root market 모듈이 소비하는 내부 전용 캘린더 엔드포인트 — 각 라우트가 MarketCalendarPort로
// 올바르게 위임하는지만 검증(얇은 pass-through라 예외 시나리오 없음)
@WebMvcTest(MarketCalendarInternalController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class, FixedClockTestConfig.class})
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
    void session_거래일을_실제_마감_30분_전_경계로_판정한다() throws Exception {
        // 고정 시각 KST 01-06 12:00 — 세션 마감을 13:00으로 두면 전환 경계(12:30) 전이라 오늘(04:30 고정 경계였다면 01-07)
        LocalDate today = LocalDate.of(2026, 1, 6);
        given(marketCalendarPort.findSession(today)).willReturn(Optional.of(new MarketSessionTimes(
                Instant.parse("2026-01-06T00:00:00Z"), Instant.parse("2026-01-06T04:00:00Z"))));
        given(marketCalendarPort.isMarketOpen(today)).willReturn(true);

        mockMvc.perform(get("/api/internal/marketcalendar/session")
                        .header("X-Internal-Token", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").doesNotExist())
                .andExpect(jsonPath("$.tradeDateKst").value("2026-01-06"))
                .andExpect(jsonPath("$.tradingDay").value(true));
    }

    @Test
    void token_없으면_401() throws Exception {
        mockMvc.perform(get("/api/internal/marketcalendar/holidays")
                        .param("year", "2026")
                        .param("month", "1"))
                .andExpect(status().isUnauthorized());
    }
}
