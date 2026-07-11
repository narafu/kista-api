package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.*;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.TokenUseCase;
import com.kista.domain.port.in.UserUseCase;
import com.kista.domain.port.out.UserPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(DevAuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "jwt.signing-key={\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU\",\"y\":\"x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0\",\"d\":\"jpsQnnGQmL-YBIffH1136cspYG6-0iY7X1fCE9-E9LI\",\"use\":\"sig\",\"alg\":\"ES256\",\"kid\":\"test-key-1\"}"
})
class DevAuthControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;         // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean UserUseCase userUseCase;
    @MockitoBean JwtIssuerService jwtIssuerService;
    @MockitoBean UserPort userPort;
    @MockitoBean TokenUseCase tokenUseCase;                // RT 발급
    @MockitoBean RefreshTokenCookieHelper cookieHelper;    // RT 쿠키 설정

    private static final UUID DEV_USER_ID  = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEV_ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final User MOCK_USER = new User(
            DEV_USER_ID, "dev-test-user", "개발 테스트 유저",
            User.UserStatus.ACTIVE, User.UserRole.USER,
            null, null, null, null,
            User.NotificationChannel.TELEGRAM
    );

    private static final User MOCK_ADMIN_USER = new User(
            DEV_ADMIN_ID, "dev-admin", "dev-admin",
            User.UserStatus.ACTIVE, User.UserRole.ADMIN,
            null, null, null, null,
            User.NotificationChannel.TELEGRAM
    );

    @BeforeEach
    void setUp() {
        // devToken/devAdminToken 공통 stub — RT 발급 + 쿠키 설정 + AT 만료시간 (issue() 반환값만 테스트별 상이)
        when(tokenUseCase.issueRefreshToken(any(), any())).thenReturn("raw-rt");
        when(cookieHelper.issue(any())).thenReturn(ResponseCookie.from("rt", "raw-rt").build());
        when(jwtIssuerService.expiresInSeconds()).thenReturn(604800L);
    }

    @Test
    void devApprove_returns_200() throws Exception {
        doNothing().when(userUseCase).approve(DEV_USER_ID);

        mockMvc.perform(post("/api/auth/dev-approve/00000000-0000-0000-0000-000000000001")
                        .with(csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void devToken_returns_token() throws Exception {
        when(userUseCase.register(any(), any(), any())).thenReturn(MOCK_USER);
        doNothing().when(userUseCase).approve(any());
        when(jwtIssuerService.issue(any(), any())).thenReturn("tok");

        mockMvc.perform(post("/api/auth/dev-token")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("tok"));
    }

    @Test
    void devAdminToken_returns_token() throws Exception {
        when(userPort.findById(any())).thenReturn(Optional.of(MOCK_ADMIN_USER));
        when(jwtIssuerService.issue(any(), any())).thenReturn("admin-tok");

        mockMvc.perform(post("/api/auth/dev-admin-token")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("admin-tok"));
    }
}
