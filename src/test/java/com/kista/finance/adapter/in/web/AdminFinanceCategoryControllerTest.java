package com.kista.finance.adapter.in.web;

import com.kista.adapter.in.web.security.InternalTokenAuthFilter;
import com.kista.adapter.in.web.security.JwtAuthFilter;
import com.kista.adapter.in.web.security.SecurityConfig;
import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.model.FinanceCategoryCommand;
import com.kista.application.usecase.BlacklistUseCase;
import com.kista.finance.application.usecase.FinanceCategoryUseCase;
import com.kista.admin.application.port.output.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// ADMIN role 가드 검증이 필요해 SecurityConfig·JwtAuthFilter를 명시적으로 @Import한다 (AdminPingControllerTest 패턴).
@WebMvcTest(AdminFinanceCategoryController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, InternalTokenAuthFilter.class})
@Execution(ExecutionMode.SAME_THREAD)
class AdminFinanceCategoryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean FinanceCategoryUseCase categoryUseCase;

    @Test
    void list_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/admin/finance/categories"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void list_userToken_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/finance/categories")
                        .with(authentication(userTokenWithRole(DEV_USER_UUID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_adminToken_returnsNestedTree() throws Exception {
        UUID l1Id = UUID.randomUUID();
        UUID l2Id = UUID.randomUUID();
        FinanceCategory l1 = new FinanceCategory(l1Id, null, null, null,
                FinanceCategory.Type.INCOME, "근로소득", 10, Instant.now());
        FinanceCategory l2 = new FinanceCategory(l2Id, null, l1Id, null,
                FinanceCategory.Type.INCOME, "급여", 10, Instant.now());
        when(categoryUseCase.listSystem(any())).thenReturn(List.of(l1, l2));

        mockMvc.perform(get("/api/admin/finance/categories")
                        .with(authentication(adminToken(DEV_ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(l1Id.toString()))
                .andExpect(jsonPath("$[0].children[0].id").value(l2Id.toString()));
    }

    @Test
    void create_adminToken_returns201WithLocationHeader() throws Exception {
        UUID savedId = UUID.randomUUID();
        FinanceCategory saved = new FinanceCategory(savedId, null, null, null,
                FinanceCategory.Type.EXPENSE, "새시스템카테고리", 0, Instant.now());
        when(categoryUseCase.createSystem(any(FinanceCategoryCommand.class))).thenReturn(saved);

        mockMvc.perform(post("/api/admin/finance/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"name\":\"새시스템카테고리\",\"sortOrder\":0}")
                        .with(csrf()).with(authentication(adminToken(DEV_ADMIN_UUID))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/finance/categories/" + savedId));
    }

    @Test
    void create_userToken_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/finance/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"name\":\"새시스템카테고리\",\"sortOrder\":0}")
                        .with(csrf()).with(authentication(userTokenWithRole(DEV_USER_UUID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withoutType_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/finance/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새시스템카테고리\",\"sortOrder\":0}")
                        .with(csrf()).with(authentication(adminToken(DEV_ADMIN_UUID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void update_adminToken_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        FinanceCategory updated = new FinanceCategory(id, null, null, null,
                FinanceCategory.Type.EXPENSE, "수정된시스템카테고리", 1, Instant.now());
        when(categoryUseCase.updateSystem(any(), any(FinanceCategoryCommand.class))).thenReturn(updated);

        mockMvc.perform(put("/api/admin/finance/categories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"수정된시스템카테고리\",\"sortOrder\":1}")
                        .with(csrf()).with(authentication(adminToken(DEV_ADMIN_UUID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("수정된시스템카테고리"));
    }

    @Test
    void update_targetNotSystem_returns400() throws Exception {
        UUID id = UUID.randomUUID();
        when(categoryUseCase.updateSystem(any(), any(FinanceCategoryCommand.class)))
                .thenThrow(new IllegalArgumentException("시스템 카테고리가 아닙니다: " + id));

        mockMvc.perform(put("/api/admin/finance/categories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"수정명\",\"sortOrder\":1}")
                        .with(csrf()).with(authentication(adminToken(DEV_ADMIN_UUID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void delete_adminToken_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/admin/finance/categories/{id}", id)
                        .with(csrf()).with(authentication(adminToken(DEV_ADMIN_UUID))))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_userToken_returns403() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/admin/finance/categories/{id}", id)
                        .with(csrf()).with(authentication(userTokenWithRole(DEV_USER_UUID))))
                .andExpect(status().isForbidden());
    }
}
