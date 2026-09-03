package com.kista.finance.adapter.in.web;

import com.kista.finance.domain.model.FinanceBudget;
import com.kista.finance.domain.model.FinanceBudgetCommand;
import com.kista.application.usecase.BlacklistUseCase;
import com.kista.finance.application.usecase.FinanceBudgetUseCase;
import com.kista.application.port.output.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.kista.support.WebMvcTestSupport.*;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FinanceBudgetController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceBudgetControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean FinanceBudgetUseCase budgetUseCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void list_withNoQueryParams_returns200() throws Exception {
        when(budgetUseCase.list(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/finance/budgets")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void list_withAllQueryParams_returns200() throws Exception {
        when(budgetUseCase.list(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/finance/budgets")
                        .param("categoryId", UUID.randomUUID().toString())
                        .param("date", "2026-08-01")
                        .param("groupId", UUID.randomUUID().toString())
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void create_returns201WithLocationHeader() throws Exception {
        UUID savedId = UUID.randomUUID();
        FinanceBudget saved = new FinanceBudget(savedId, null, UUID.randomUUID(), USER_ID,
                LocalDate.of(2026, 1, 1), null, 350000L, Instant.now());
        when(budgetUseCase.create(any(), any(), any(FinanceBudgetCommand.class))).thenReturn(saved);

        mockMvc.perform(post("/api/finance/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + saved.categoryId() + "\",\"applyStartDate\":\"2026-01-01\",\"amount\":350000}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/finance/budgets/" + savedId));
    }

    @Test
    void create_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/finance/budgets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + UUID.randomUUID() + "\",\"applyStartDate\":\"2026-01-01\",\"amount\":350000}")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        FinanceBudget updated = new FinanceBudget(id, null, categoryId, USER_ID,
                LocalDate.of(2026, 1, 1), null, 400000L, Instant.now());
        when(budgetUseCase.update(any(), any(), any(FinanceBudgetCommand.class))).thenReturn(updated);

        mockMvc.perform(put("/api/finance/budgets/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + categoryId + "\",\"applyStartDate\":\"2026-01-01\",\"amount\":400000}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(400000));
    }

    @Test
    void delete_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/finance/budgets/{id}", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());

        verify(budgetUseCase).delete(id, USER_ID);
    }

    @Test
    void share_returns200WithGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        FinanceBudget shared = new FinanceBudget(id, groupId, UUID.randomUUID(), USER_ID,
                LocalDate.of(2026, 1, 1), null, 350000L, Instant.now());
        when(budgetUseCase.shareToGroup(id, USER_ID)).thenReturn(shared);

        mockMvc.perform(patch("/api/finance/budgets/{id}/share", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()));
    }

    @Test
    void unshare_returns200WithNullGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        FinanceBudget personal = new FinanceBudget(id, null, UUID.randomUUID(), USER_ID,
                LocalDate.of(2026, 1, 1), null, 350000L, Instant.now());
        when(budgetUseCase.unshare(id, USER_ID)).thenReturn(personal);

        mockMvc.perform(patch("/api/finance/budgets/{id}/unshare", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()));
    }
}
