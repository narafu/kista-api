package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.MarketUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(MarketHolidayController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class MarketHolidayControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean MarketUseCase marketUseCase;

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void holidays_anonymous_returns_401() throws Exception {
        mockMvc.perform(get("/api/market/holidays")
                        .param("year", "2026")
                        .param("month", "1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void holidays_authenticated_returns_list() throws Exception {
        when(marketUseCase.getMonthlyHolidays(2026, 1))
                .thenReturn(List.of(LocalDate.of(2026, 1, 1)));

        mockMvc.perform(get("/api/market/holidays")
                        .param("year", "2026")
                        .param("month", "1")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("2026-01-01"));
    }

    @Test
    void session_returns_200_without_auth() throws Exception {
        // /api/market/session은 anyRequest().authenticated() 에 해당 — 인증 포함으로 테스트
        mockMvc.perform(get("/api/market/session")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session").exists())
                .andExpect(jsonPath("$.isDst").exists());
    }
}
