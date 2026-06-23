package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.*;
import com.kista.domain.model.auth.InvalidRefreshTokenException;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.GetUserSettingsQuery;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.in.UserUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class,
         RefreshTokenCookieHelper.class})
@Execution(ExecutionMode.SAME_THREAD)
class AuthControllerTokenTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean UserUseCase userUseCase;
    @MockitoBean TokenUseCase tokenUseCase;
    @MockitoBean JwtIssuerService jwtIssuerService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean GetUserSettingsQuery getUserSettingsQuery;
    @MockitoBean com.kista.adapter.out.sse.SseEmitterRegistry sseEmitterRegistry;

    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    void refresh_validCookie_returns200AndSetsNewCookie() throws Exception {
        var result = new TokenRefreshResult(USER_ID, User.UserRole.USER, "newRawRt");
        User user = stubUser(USER_ID);
        given(tokenUseCase.refresh(anyString(), any())).willReturn(result);
        given(userUseCase.getById(any())).willReturn(user);
        given(jwtIssuerService.issue(any(), any())).willReturn("newAt");
        given(jwtIssuerService.expiresInSeconds()).willReturn(900L);

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "someRawRt")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("newAt"))
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void refresh_noCookie_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void refresh_invalidToken_returns401() throws Exception {
        given(tokenUseCase.refresh(anyString(), any()))
                .willThrow(new InvalidRefreshTokenException("유효하지 않은 refresh token"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "badToken")))
                .andExpect(status().isUnauthorized()); // GlobalExceptionHandler: InvalidRefreshTokenException → 401
    }

    @Test
    void logout_withCookie_returns204AndClearsCookie() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "someRt")))
                .andExpect(status().isNoContent())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE)); // maxAge=0 쿠키
    }

    @Test
    void logout_noCookie_returns204() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("jti 블랙리스트 차단 — 401 + JSON body TOKEN_BLACKLISTED 반환")
    void request_jtiBlacklisted_returns401WithJsonBody() throws Exception {
        String jti = "blacklisted-jti";
        Jwt jwt = Jwt.withTokenValue("test-at")
                .header("alg", "ES256")
                .jti(jti)
                .claim("sub", USER_ID.toString())
                .claim("role", "USER")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        given(jwtDecoder.decode(anyString())).willReturn(jwt);
        given(blacklistUseCase.isJtiBlacklisted(jti)).willReturn(true); // jti 블랙리스트 hit
        given(blacklistUseCase.isBlacklisted(any())).willReturn(false);

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer test-at"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.error").value("TOKEN_BLACKLISTED"));
    }

    @Test
    @DisplayName("userId 블랙리스트 차단 — 401 + JSON body TOKEN_BLACKLISTED 반환")
    void request_userIdBlacklisted_returns401WithJsonBody() throws Exception {
        String jti = "valid-jti";
        Jwt jwt = Jwt.withTokenValue("test-at")
                .header("alg", "ES256")
                .jti(jti)
                .claim("sub", USER_ID.toString())
                .claim("role", "USER")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        given(jwtDecoder.decode(anyString())).willReturn(jwt);
        given(blacklistUseCase.isJtiBlacklisted(jti)).willReturn(false); // jti 정상
        given(blacklistUseCase.isBlacklisted(USER_ID)).willReturn(true); // userId 블랙리스트 hit

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer test-at"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.error").value("TOKEN_BLACKLISTED"));
    }

    @Test
    @DisplayName("jti null이어도 userId 블랙리스트 체크 수행 — 차단 시 401 반환")
    void request_jtiNullUserIdBlacklisted_returns401() throws Exception {
        // jti 없는 JWT (getId() → null)
        Jwt jwt = Jwt.withTokenValue("test-at")
                .header("alg", "ES256")
                .claim("sub", USER_ID.toString())
                .claim("role", "USER")
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        given(jwtDecoder.decode(anyString())).willReturn(jwt);
        given(blacklistUseCase.isBlacklisted(USER_ID)).willReturn(true); // userId 블랙리스트 hit

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer test-at"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("TOKEN_BLACKLISTED"));
    }

    private User stubUser(UUID id) {
        return new User(id, "kakaoId", "닉네임",
                User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, User.NotificationChannel.FCM);
    }
}
