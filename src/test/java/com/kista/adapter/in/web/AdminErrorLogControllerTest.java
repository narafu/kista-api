package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.admin.AppErrorLog;
import com.kista.domain.port.out.AppErrorLogPort;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminErrorLogController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminErrorLogControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean AppErrorLogPort appErrorLogPort;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listErrorLogs_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/error-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listErrorLogs_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/error-logs")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listErrorLogs_adminRole_returns200_with_list() throws Exception {
        AppErrorLog log = new AppErrorLog(
                UUID.randomUUID(), "KisApiException", "KIS 오류", "stack...",
                Map.of("caller", "TradingService"), Instant.now()
        );
        when(appErrorLogPort.findRecent(100)).thenReturn(List.of(log));

        mockMvc.perform(get("/api/admin/error-logs")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].errorType").value("KisApiException"));
    }

    @Test
    void listErrorLogs_customLimit_passedToPort() throws Exception {
        when(appErrorLogPort.findRecent(50)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/error-logs?limit=50")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
