package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.admin.AdminStats;
import com.kista.domain.port.in.AdminDashboardUseCase;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDashboardController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminDashboardControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean JwtDecoder jwtDecoder;
    @MockBean AdminDashboardUseCase dashboardUseCase;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void getStats_withAdminToken_returns200() throws Exception {
        when(dashboardUseCase.getStats()).thenReturn(new AdminStats(10, 3, 5, 2, 7));

        var adminToken = new UsernamePasswordAuthenticationToken(ADMIN_UUID, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        mockMvc.perform(get("/api/admin/dashboard/stats").with(authentication(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(10))
                .andExpect(jsonPath("$.pendingCount").value(3))
                .andExpect(jsonPath("$.totalAccounts").value(7));
    }

    @Test
    void getStats_withUserToken_returns403() throws Exception {
        var userToken = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mockMvc.perform(get("/api/admin/dashboard/stats").with(authentication(userToken)))
                .andExpect(status().isForbidden());
    }
}
