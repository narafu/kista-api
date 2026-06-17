package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.*;
import com.kista.domain.model.auth.TokenRefreshResult;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.in.UserUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class,
         RefreshTokenCookieHelper.class})
@Execution(ExecutionMode.SAME_THREAD)
class AuthControllerTokenTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean UserUseCase userUseCase;
    @MockitoBean TokenUseCase tokenUseCase;
    @MockitoBean JwtIssuerService jwtIssuerService;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
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
    void refresh_invalidToken_returns404() throws Exception {
        given(tokenUseCase.refresh(anyString(), any()))
                .willThrow(new NoSuchElementException("유효하지 않은 refresh token"));

        mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("refresh_token", "badToken")))
                .andExpect(status().isNotFound()); // GlobalExceptionHandler: NoSuchElementException → 404
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

    private User stubUser(UUID id) {
        return new User(id, "kakaoId", "닉네임",
                User.UserStatus.ACTIVE, User.UserRole.USER,
                null, null, null, null, User.NotificationChannel.FCM, true);
    }
}
