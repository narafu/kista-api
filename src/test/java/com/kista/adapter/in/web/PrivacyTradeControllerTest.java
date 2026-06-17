package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.privacy.PrivacyCurrentBase;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.PrivacyUseCase;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PrivacyTradeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class PrivacyTradeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean PrivacyUseCase privacy;

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void getLatestBase_anonymous_returns_401() throws Exception {
        mockMvc.perform(get("/api/privacy-trades/base/latest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getLatestBase_authenticated_returns_200() throws Exception {
        PrivacyCurrentBase base = new PrivacyCurrentBase(
                Ticker.SOXL,
                new BigDecimal("25.43"),
                LocalDate.of(2026, 1, 2)
        );
        when(privacy.getPrivacyCurrentBase()).thenReturn(base);

        mockMvc.perform(get("/api/privacy-trades/base/latest")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("SOXL"));
    }

    @Test
    void getLatestBase_not_found_returns_404() throws Exception {
        when(privacy.getPrivacyCurrentBase()).thenThrow(new NoSuchElementException("없음"));

        mockMvc.perform(get("/api/privacy-trades/base/latest")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isNotFound());
    }
}
