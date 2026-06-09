package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.adapter.out.sse.SseEmitterRegistry;
import com.kista.domain.model.account.Account;
import com.kista.domain.port.in.UserUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UserUseCase userUseCase;
    @MockitoBean SseEmitterRegistry sseEmitterRegistry;
    @MockitoBean JwtDecoder jwtDecoder; // JwtDecoderConfig bean — WebMvcTest에서 명시 필요
    @MockitoBean JwtIssuerService jwtIssuerService;   // JWT 발급 서비스

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Authentication auth() {
        // @AuthenticationPrincipal UUID 바인딩을 위해 principal을 UUID로 설정
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    @DisplayName("쿨다운 중 재신청 시 429 Too Many Requests 반환")
    void reapply_cooldown_returns_429() throws Exception {
        Instant retryAfter = Instant.now().plus(1, ChronoUnit.HOURS);
        doThrow(new Account.CooldownException(retryAfter)).when(userUseCase).reapply(USER_ID);

        mockMvc.perform(post("/api/auth/approval-requests")
                        .with(csrf())
                        .with(authentication(auth())))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("회원 탈퇴 — 인증 후 204 반환")
    void deleteMe_authenticated_returns204() throws Exception {
        mockMvc.perform(delete("/api/auth/me")
                        .with(csrf())
                        .with(authentication(auth())))
                .andExpect(status().isNoContent());

        verify(userUseCase).deleteMe(USER_ID); // UseCase 실제 호출 검증
    }

    @Test
    @DisplayName("존재하지 않는 사용자 탈퇴 시 404 반환")
    void deleteMe_userNotFound_returns404() throws Exception {
        doThrow(new NoSuchElementException()).when(userUseCase).deleteMe(any());

        mockMvc.perform(delete("/api/auth/me")
                        .with(csrf())
                        .with(authentication(auth())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("회원 탈퇴 — 비인증 시 401 반환")
    void deleteMe_anonymous_returns401() throws Exception {
        mockMvc.perform(delete("/api/auth/me").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
