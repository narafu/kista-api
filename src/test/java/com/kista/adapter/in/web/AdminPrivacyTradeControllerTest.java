package com.kista.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.privacy.PrivacyTradeBaseView;
import com.kista.domain.port.in.AdminQueryUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(AdminPrivacyTradeController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminPrivacyTradeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AdminQueryUseCase adminQuery;

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID USER_UUID  = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void listBases_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/privacy-trade-bases"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listBases_userRole_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/privacy-trade-bases")
                        .with(authentication(token(USER_UUID, "ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void listBases_adminRange30_returns200_andPassesDays30() throws Exception {
        var view = new PrivacyTradeBaseView(UUID.randomUUID(), LocalDate.of(2026, 6, 10), "SOXL",
                new BigDecimal("28.50"), new BigDecimal("45.20"), new BigDecimal("27.80"), 120,
                List.of(new PrivacyTradeBaseView.OrderLine(UUID.randomUUID(), "BUY", "LOC", new BigDecimal("14.25"), 60)));
        when(adminQuery.listPrivacyBases(30)).thenReturn(List.of(view));

        mockMvc.perform(get("/api/admin/privacy-trade-bases?range=30")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ticker").value("SOXL"))
                .andExpect(jsonPath("$[0].orders[0].direction").value("BUY"));
        verify(adminQuery).listPrivacyBases(30);
    }

    @Test
    void listBases_defaultRange_passesNull() throws Exception {
        when(adminQuery.listPrivacyBases(isNull())).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/privacy-trade-bases")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isOk());
        verify(adminQuery).listPrivacyBases(isNull());
    }

    @Test
    void listBases_invalidRange_returns400() throws Exception {
        mockMvc.perform(get("/api/admin/privacy-trade-bases?range=7")
                        .with(authentication(token(ADMIN_UUID, "ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
    }

    private static UsernamePasswordAuthenticationToken token(UUID uuid, String role) {
        return new UsernamePasswordAuthenticationToken(uuid, null,
                List.of(new SimpleGrantedAuthority(role)));
    }
}
