package com.kista.web;

import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// SecurityConfig + RootSecurityPolicy 조합의 root 라우트 인가 결과 — 규칙 순서(구체 경로가 /api/auth/** permitAll보다 먼저)를 잠근다
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
    void authWildcard_anonymousIs200_butWithdrawIs401() throws Exception {
        mockMvc.perform(get("/api/auth/probe")).andExpect(status().isOk());
        mockMvc.perform(delete("/api/auth/me")).andExpect(status().isUnauthorized());
    }
}
