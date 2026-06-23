package com.kista.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.domain.model.admin.AdminUserView;
import com.kista.domain.model.user.User;
import com.kista.domain.port.in.AdminUserUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kista.domain.port.out.AppErrorLogPort;

@WebMvcTest(AdminUserController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD) // 병렬 실행 mock 오염 방지
class AdminUserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;         // JwtDecoderConfig 실제 빈 생성 방지
    @MockitoBean BlacklistUseCase blacklistUseCase; // JwtAuthFilter 블랙리스트 체크 의존성
    @MockitoBean AdminUserUseCase adminUser;    // 컨트롤러 의존성 주입용

    private static final UUID ADMIN_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // ADMIN 권한 토큰 생성 헬퍼
    private UsernamePasswordAuthenticationToken adminToken() {
        return new UsernamePasswordAuthenticationToken(ADMIN_UUID, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    // 테스트용 샘플 AdminUserView 생성
    private AdminUserView sampleUser(UUID id) {
        return new AdminUserView(id, "테스트유저", User.UserStatus.PENDING, User.UserRole.USER, Instant.now());
    }

    @Test
    void listUsers_withAdminToken_returns200() throws Exception {
        UUID userId = UUID.randomUUID();
        when(adminUser.listAll(null, null)).thenReturn(List.of(sampleUser(userId)));

        mockMvc.perform(get("/api/admin/users").with(authentication(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    void listUsers_withUserToken_returns403() throws Exception {
        // USER 권한으로 /api/admin/** 접근 시 403 반환
        var userToken = new UsernamePasswordAuthenticationToken(UUID.randomUUID(), null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mockMvc.perform(get("/api/admin/users").with(authentication(userToken)))
                .andExpect(status().isForbidden());
    }

    @Test
    void approveUser_withAdminToken_returns204() throws Exception {
        doNothing().when(adminUser).approveUser(any(), any());

        mockMvc.perform(patch("/api/admin/users/{id}/status", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}")
                        .with(authentication(adminToken())))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectUser_withAdminToken_returns204() throws Exception {
        doNothing().when(adminUser).rejectUser(any(), any());

        mockMvc.perform(patch("/api/admin/users/{id}/status", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"REJECTED\"}")
                        .with(authentication(adminToken())))
                .andExpect(status().isNoContent());
    }

    @Test
    void changeRole_withAdminToken_returns204() throws Exception {
        doNothing().when(adminUser).changeRole(any(), any(), any());

        mockMvc.perform(patch("/api/admin/users/{id}/role", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}")
                        .with(authentication(adminToken())))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUser_withAdminToken_returns204() throws Exception {
        doNothing().when(adminUser).deleteUser(any(), any());

        mockMvc.perform(delete("/api/admin/users/{id}", UUID.randomUUID())
                        .with(authentication(adminToken())))
                .andExpect(status().isNoContent());
    }
}
