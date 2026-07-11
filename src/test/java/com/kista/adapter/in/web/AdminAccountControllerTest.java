package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.AdminStrategyUseCase;
import com.kista.domain.port.in.AdminUserUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(AdminAccountController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminAccountControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AdminQueryUseCase adminQuery;
    @MockitoBean AdminUserUseCase adminUser;
    @MockitoBean AdminStrategyUseCase adminStrategy;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listAccounts_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/accounts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listAccounts_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/accounts")
                        .with(authentication(userTokenWithRole(USER_UUID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listAccounts_adminRole_returns200() throws Exception {
        when(adminQuery.listAccounts(null, null)).thenReturn(List.of());
        when(adminUser.listAll(null, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/accounts")
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void listStrategiesByAccount_adminRole_returns200() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(adminQuery.listStrategies(accountId)).thenReturn(List.of(
                new Strategy(UUID.randomUUID(), accountId, Strategy.Type.PRIVACY, Strategy.Status.ACTIVE,
                        Strategy.Ticker.SOXL, Strategy.CycleSeedType.MAX)));

        mockMvc.perform(get("/api/admin/accounts/{accountId}/strategies", accountId)
                        .with(authentication(adminToken(ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PRIVACY"))
                .andExpect(jsonPath("$[0].ticker").value("SOXL"));
    }

    @Test
    void pauseStrategy_adminRole_returns204() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        doNothing().when(adminStrategy).pauseStrategy(ADMIN_UUID, accountId, strategyId);

        mockMvc.perform(patch("/api/admin/accounts/{accountId}/strategies/{strategyId}/status", accountId, strategyId)
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .contentType("application/json")
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void resumeStrategy_adminRole_returns204() throws Exception {
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        doNothing().when(adminStrategy).resumeStrategy(ADMIN_UUID, accountId, strategyId);

        mockMvc.perform(patch("/api/admin/accounts/{accountId}/strategies/{strategyId}/status", accountId, strategyId)
                        .with(authentication(adminToken(ADMIN_UUID)))
                        .contentType("application/json")
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNoContent());
    }
}
