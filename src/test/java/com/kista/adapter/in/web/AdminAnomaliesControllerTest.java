package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.AdminAnomalies;
import com.kista.domain.port.in.AdminAnomaliesUseCase;
import com.kista.domain.port.in.AdminListAccountsUseCase;
import com.kista.domain.port.in.AdminListUsersUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminAnomaliesController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminAnomaliesControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminAnomaliesUseCase anomaliesUseCase;
    @MockBean AdminListAccountsUseCase listAccounts;
    @MockBean AdminListUsersUseCase listUsers;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void getAnomalies_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/anomalies"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getAnomalies_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/anomalies")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getAnomalies_adminRole_returns200() throws Exception {
        when(anomaliesUseCase.getAnomalies())
                .thenReturn(new AdminAnomalies(List.of(), List.of(), List.of()));
        when(listAccounts.listAll()).thenReturn(List.of());
        when(listUsers.listAll()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/anomalies")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failedTrades").isArray())
                .andExpect(jsonPath("$.pausedAccounts").isArray())
                .andExpect(jsonPath("$.inactiveAccounts").isArray());
    }

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
