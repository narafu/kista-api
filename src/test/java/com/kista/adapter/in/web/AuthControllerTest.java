package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtIssuerService;
import com.kista.adapter.in.web.security.RefreshTokenCookieHelper;
import com.kista.adapter.out.sse.SseEmitterRegistry;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.GetUserSettingsQuery;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.in.UserUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(AuthController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean UserUseCase userUseCase;
    @MockitoBean TokenUseCase tokenUseCase; // AuthController RT 발급 의존성
    @MockitoBean RefreshTokenCookieHelper cookieHelper; // RT 쿠키 헬퍼 의존성
    @MockitoBean SseEmitterRegistry sseEmitterRegistry;
    @MockitoBean JwtDecoder jwtDecoder; // JwtDecoderConfig bean — WebMvcTest에서 명시 필요
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean JwtIssuerService jwtIssuerService;   // JWT 발급 서비스
    @MockitoBean GetUserSettingsQuery getUserSettingsQuery; // AuthController.me() / kakaoCallback() 의존성

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        // deleteMe가 cookieHelper.clear().toString()을 호출 — mock 기본값 null 방지
        given(cookieHelper.clear()).willReturn(
                ResponseCookie.from("refresh_token", "").maxAge(0).build());
    }

    @Test
    @DisplayName("쿨다운 중 재신청 시 429 Too Many Requests 반환")
    void reapply_cooldown_returns_429() throws Exception {
        Instant retryAfter = Instant.now().plus(1, ChronoUnit.HOURS);
        doThrow(new User.CooldownException(retryAfter)).when(userUseCase).reapply(USER_ID);

        mockMvc.perform(post("/api/auth/approval-requests")
                        .with(csrf())
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("회원 탈퇴 — 인증 후 204 반환")
    void deleteMe_authenticated_returns204() throws Exception {
        mockMvc.perform(delete("/api/auth/me")
                        .with(csrf())
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());

        verify(userUseCase).deleteMe(USER_ID); // UseCase 실제 호출 검증
    }

    @Test
    @DisplayName("존재하지 않는 사용자 탈퇴 시 404 반환")
    void deleteMe_userNotFound_returns404() throws Exception {
        doThrow(new NoSuchElementException()).when(userUseCase).deleteMe(any());

        mockMvc.perform(delete("/api/auth/me")
                        .with(csrf())
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("회원 탈퇴 — 비인증 시 401 반환")
    void deleteMe_anonymous_returns401() throws Exception {
        mockMvc.perform(delete("/api/auth/me").with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout — AT Bearer 헤더 있을 때 jti 추출하여 tokenUseCase.logout() 전달")
    void logout_withValidAt_passesJtiToUseCase() throws Exception {
        String rawRt = "test-raw-rt";
        String jti = "test-jti";
        Instant expiresAt = Instant.now().plusSeconds(3600);

        // cookieHelper.extract()가 rawRt를 반환하도록 stub
        given(cookieHelper.extract(any())).willReturn(rawRt);

        // jwtDecoder.decode()가 jti + expiresAt을 담은 Jwt 객체를 반환하도록 stub
        Jwt jwt = Jwt.withTokenValue("test-at")
                .header("alg", "ES256")
                .claim("sub", USER_ID.toString())
                .claim("jti", jti)
                .expiresAt(expiresAt)
                .build();
        given(jwtDecoder.decode(anyString())).willReturn(jwt);

        mockMvc.perform(post("/api/auth/logout")
                        .with(csrf())
                        .with(authentication(userToken(USER_ID)))
                        .header("Authorization", "Bearer test-at")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", rawRt)))
                .andExpect(status().isNoContent());

        // jti와 expiresAt이 올바르게 전달됐는지 검증
        ArgumentCaptor<String> jtiCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expiresAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(tokenUseCase).logout(anyString(), jtiCaptor.capture(), expiresAtCaptor.capture());
        assertThat(jtiCaptor.getValue()).isEqualTo(jti);
        assertThat(expiresAtCaptor.getValue()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("logout — Authorization 헤더 없을 때 jti=null, expiresAt=null로 tokenUseCase.logout() 호출")
    void logout_withoutAt_passesNullJtiToUseCase() throws Exception {
        String rawRt = "test-raw-rt-no-at";

        // cookieHelper.extract()가 rawRt를 반환하도록 stub
        given(cookieHelper.extract(any())).willReturn(rawRt);

        mockMvc.perform(post("/api/auth/logout")
                        .with(csrf())
                        .with(authentication(userToken(USER_ID)))
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", rawRt)))
                .andExpect(status().isNoContent());

        // jti=null, expiresAt=null로 호출됐는지 검증
        verify(tokenUseCase).logout(anyString(), isNull(), isNull());
    }
}
