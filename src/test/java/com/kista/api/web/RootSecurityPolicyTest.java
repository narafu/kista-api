package com.kista.api.web;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SecurityConfig + RootSecurityPolicy 조합의 root 라우트 인가 결과 — 공개 경로는 명시한 것뿐이고 나머지 /api/auth/**는 인증이 필요하다
@WebMvcTest(RootSecurityPolicyTest.ProbeController.class)
@Import({SecurityConfig.class, RootSecurityPolicy.class, JwtAuthFilter.class, InternalTokenAuthFilter.class,
        RootSecurityPolicyTest.ProbeController.class})
@Execution(ExecutionMode.SAME_THREAD)
class RootSecurityPolicyTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성

    @RestController
    static class ProbeController {
        @GetMapping("/api/meta")
        String meta() { return "meta"; }

        @GetMapping("/api/trades/stream")
        String tradeStream() { return "stream"; }

        @GetMapping("/api/auth/probe")
        String authOpen() { return "auth"; }

        @DeleteMapping("/api/auth/me")
        String withdraw() { return "bye"; }

        @GetMapping("/api/auth/me")
        String me() { return "me"; }

        @PostMapping("/api/auth/approval-requests")
        String reapply() { return "reapply"; }

        @PostMapping("/api/auth/logout")
        String logout() { return "logout"; }

        @PostMapping("/api/auth/kakao/callback")
        String kakaoCallback() { return "login"; }

        @PostMapping("/api/auth/refresh")
        String refresh() { return "refresh"; }

        @GetMapping("/api/auth/status-stream")
        String statusStream() { return "status"; }

        @PostMapping({"/api/auth/dev-token", "/api/auth/dev-approve/{userId}"})
        String dev() { return "dev"; }

        @PostMapping("/api/client-errors")
        String clientErrors() { return "ok"; }

        @GetMapping("/api/market/fear-greed")
        String market() { return "market"; }

        @GetMapping("/api/accounts/probe")
        String accounts() { return "accounts"; }
    }

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String EXPIRED = "Bearer expired"; // 디코딩 실패(만료 포함)
    private static final String BLACKLISTED = "Bearer blacklisted"; // jti 블랙리스트
    private static final String STALE = "Bearer stale"; // role 변경 이전 발급

    private static Jwt jwt(String value, String jti, Instant issuedAt) {
        return Jwt.withTokenValue(value).header("alg", "ES256").subject(USER_ID.toString()).jti(jti).issuedAt(issuedAt).build();
    }

    private void stubInvalidTokens() {
        Instant now = Instant.now();
        when(jwtDecoder.decode("expired")).thenThrow(new BadJwtException("expired"));
        when(jwtDecoder.decode("blacklisted")).thenReturn(jwt("blacklisted", "jti-blk", now));
        when(jwtDecoder.decode("stale")).thenReturn(jwt("stale", "jti-stale", now.minusSeconds(60)));
        when(tokenBlacklistPort.isJtiBlacklisted("jti-blk")).thenReturn(true);
        when(tokenBlacklistPort.isJtiBlacklisted("jti-stale")).thenReturn(false);
        when(tokenBlacklistPort.isBlacklisted(any())).thenReturn(false);
        when(tokenBlacklistPort.roleChangedAt(USER_ID)).thenReturn(now);
    }

    @Test
    void meta_anonymousIs200() throws Exception {
        mockMvc.perform(get("/api/meta")).andExpect(status().isOk());
    }

    @Test
    void tradeStream_anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/trades/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void authRoutes_onlyListedPathsAreOpen() throws Exception {
        for (String open : new String[]{"/api/auth/kakao/callback", "/api/auth/refresh", "/api/auth/logout",
                "/api/auth/dev-token", "/api/auth/dev-approve/00000000-0000-0000-0000-000000000001"}) {
            mockMvc.perform(post(open)).andExpect(status().isOk());
        }
        // 목록에 없는 /api/auth 경로는 새로 생겨도 기본이 인증 필요다
        mockMvc.perform(get("/api/auth/probe")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/status-stream")).andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/refresh")).andExpect(status().isUnauthorized()); // 메서드 불일치
    }

    @Test
    void publicReadRoutes_invalidTokenFallsBackToAnonymous() throws Exception {
        stubInvalidTokens();
        for (String token : new String[]{EXPIRED, BLACKLISTED, STALE}) {
            mockMvc.perform(get("/api/meta").header("Authorization", token)).andExpect(status().isOk());
            mockMvc.perform(get("/api/market/fear-greed").header("Authorization", token)).andExpect(status().isOk());
            mockMvc.perform(post("/api/client-errors").header("Authorization", token)).andExpect(status().isOk());
        }
    }

    @Test
    void nonPublicRoutes_invalidTokenStays401() throws Exception {
        stubInvalidTokens();
        mockMvc.perform(post("/api/meta").header("Authorization", EXPIRED)).andExpect(status().isUnauthorized()); // 메서드 불일치
        mockMvc.perform(get("/api/accounts/probe").header("Authorization", EXPIRED)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/accounts/probe").header("Authorization", BLACKLISTED))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("TOKEN_BLACKLISTED"));
    }

    @Test
    void authRoutes_invalidTokenStays401WithErrorBody() throws Exception {
        stubInvalidTokens();
        mockMvc.perform(get("/api/auth/me").header("Authorization", EXPIRED))
                .andExpect(status().isUnauthorized()).andExpect(content().string(""));
        mockMvc.perform(get("/api/auth/me").header("Authorization", BLACKLISTED))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("TOKEN_BLACKLISTED"));
        mockMvc.perform(get("/api/auth/me").header("Authorization", STALE))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("TOKEN_STALE_ROLE"));
        mockMvc.perform(post("/api/auth/logout").header("Authorization", EXPIRED)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout").header("Authorization", BLACKLISTED))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("TOKEN_BLACKLISTED"));
    }

    @Test
    void principalReadingAuthRoutes_anonymousIs401_butLogoutStaysOpen() throws Exception {
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/approval-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isOk());
    }
}
