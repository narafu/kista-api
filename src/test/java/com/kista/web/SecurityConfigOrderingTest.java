package com.kista.web;

import com.kista.platform.security.InternalTokenAuthFilter;
import com.kista.platform.security.JwtAuthFilter;
import com.kista.platform.security.SecurityConfig;
import com.kista.platform.security.SecurityRoutePolicy;
import com.kista.platform.security.TokenBlacklistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.kista.support.WebMvcTestSupport.userTokenWithRole;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

// 셸이 기여하는 SecurityRoutePolicy가 /api/admin/**·/api/internal/**을 permitAll로 뚫으려 해도 공통 규칙이 먼저 매치돼야 한다 —
// SecurityConfig의 규칙 순서(공통 permitAll → internal → admin → 셸 정책 → anyRequest) 잠금 테스트
@WebMvcTest(SecurityConfigOrderingTest.ProbeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class, SecurityConfigOrderingTest.MaliciousPolicyConfig.class,
        SecurityConfigOrderingTest.ProbeController.class})
@Execution(ExecutionMode.SAME_THREAD)
class SecurityConfigOrderingTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder; // JwtAuthFilter 의존성
    @MockitoBean TokenBlacklistPort tokenBlacklistPort; // JwtAuthFilter 블랙리스트 체크 의존성

    // 사고 재현용 — admin/internal/공개 경로를 전부 permitAll로 기여하는 셸 정책
    @TestConfiguration
    static class MaliciousPolicyConfig {
        @Bean
        SecurityRoutePolicy permitAllEverything() {
            return registry -> {
                registry.requestMatchers("/api/admin/**", "/api/internal/**").permitAll();
                registry.requestMatchers("/api/probe/public").permitAll();
            };
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/api/admin/probe")
        String admin() { return "admin"; }

        @GetMapping("/api/internal/probe")
        String internal() { return "internal"; }

        @GetMapping("/api/probe/public")
        String open() { return "public"; }

        @GetMapping("/api/probe/private")
        String secured() { return "private"; }
    }

    @Test
    void adminRoute_shellPermitAllDoesNotOverrideAdminRule_anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/admin/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRoute_shellPermitAllDoesNotOverrideAdminRule_userRoleIs403() throws Exception {
        mockMvc.perform(get("/api/admin/probe")
                        .with(authentication(userTokenWithRole(UUID.fromString("00000000-0000-0000-0000-000000000001")))))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalRoute_shellPermitAllDoesNotOverrideInternalRule_anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/internal/probe"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shellContributedRoute_isAppliedBeforeAnyRequestAuthenticated() throws Exception {
        mockMvc.perform(get("/api/probe/public"))
                .andExpect(status().isOk());
        // 기여하지 않은 경로는 anyRequest().authenticated()로 떨어진다
        mockMvc.perform(get("/api/probe/private"))
                .andExpect(status().isUnauthorized());
    }
}
