package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.adapter.out.sse.SseEmitterRegistry;
import com.kista.domain.model.CooldownException;
import com.kista.domain.port.in.ApproveUserUseCase;
import com.kista.domain.port.in.GetUserUseCase;
import com.kista.domain.port.in.KakaoLoginUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean ApproveUserUseCase approveUser;
    @MockBean GetUserUseCase getUser;
    @MockBean SseEmitterRegistry sseEmitterRegistry;
    @MockBean JwtDecoder jwtDecoder; // JwtDecoderConfig bean — WebMvcTest에서 명시 필요
    @MockBean KakaoLoginUseCase kakaoLoginUseCase; // 카카오 로그인 유스케이스
    @MockBean JwtIssuerService jwtIssuerService;   // JWT 발급 서비스

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private Authentication auth() {
        // @AuthenticationPrincipal UUID 바인딩을 위해 principal을 UUID로 설정
        return new UsernamePasswordAuthenticationToken(USER_ID, null, List.of());
    }

    @Test
    @DisplayName("쿨다운 중 재신청 시 429 Too Many Requests 반환")
    void reapply_cooldown_returns_429() throws Exception {
        Instant retryAfter = Instant.now().plus(1, ChronoUnit.HOURS);
        doThrow(new CooldownException(retryAfter)).when(approveUser).reapply(USER_ID);

        mockMvc.perform(post("/api/auth/reapply")
                        .with(csrf())
                        .with(authentication(auth())))
                .andExpect(status().isTooManyRequests());
    }
}
