package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.market.FearGreedRating;
import com.kista.domain.model.market.FearGreedSnapshot;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.GetFearGreedUseCase;
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(FearGreedController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class FearGreedControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean GetFearGreedUseCase getFearGreedUseCase;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }

    @Test
    void getFearGreed_returns_cnn_and_crypto_bundle() throws Exception {
        when(getFearGreedUseCase.getRecent(eq("CNN"), anyInt())).thenReturn(List.of(
                new FearGreedSnapshot(null, "CNN", LocalDate.of(2026, 6, 21), 60, FearGreedRating.GREED, null),
                new FearGreedSnapshot(null, "CNN", LocalDate.of(2026, 6, 22), 72, FearGreedRating.GREED, null)));
        when(getFearGreedUseCase.getRecent(eq("CRYPTO"), anyInt())).thenReturn(List.of(
                new FearGreedSnapshot(null, "CRYPTO", LocalDate.of(2026, 6, 22), 30, FearGreedRating.FEAR, null)));

        mockMvc.perform(get("/api/market/fear-greed")
                        .param("days", "90")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cnn.current.value").value(72))
                .andExpect(jsonPath("$.cnn.current.rating").value("GREED"))
                .andExpect(jsonPath("$.cnn.history.length()").value(2))
                .andExpect(jsonPath("$.crypto.current.value").value(30));
    }

    @Test
    void getFearGreed_anonymous_returns_401() throws Exception {
        mockMvc.perform(get("/api/market/fear-greed").param("days", "90"))
                .andExpect(status().isUnauthorized());
    }
}
