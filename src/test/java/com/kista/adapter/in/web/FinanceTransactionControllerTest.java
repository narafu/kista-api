package com.kista.adapter.in.web;

import com.kista.domain.model.finance.FinanceTransaction;
import com.kista.domain.model.finance.FinanceTransactionCommand;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.in.FinanceTransactionUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
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

@WebMvcTest(FinanceTransactionController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceTransactionControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean FinanceTransactionUseCase transactionUseCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void list_withNoQueryParams_returns200() throws Exception {
        when(transactionUseCase.list(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/finance/transactions")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void list_withAllQueryParams_returns200() throws Exception {
        when(transactionUseCase.list(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/finance/transactions")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .param("categoryId", UUID.randomUUID().toString())
                        .param("createdBy", USER_ID.toString())
                        .param("groupId", UUID.randomUUID().toString())
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void create_returns201WithLocationHeader() throws Exception {
        UUID savedId = UUID.randomUUID();
        FinanceTransaction saved = new FinanceTransaction(savedId, null, UUID.randomUUID(), USER_ID,
                LocalDate.of(2026, 8, 1), 344000L, null, Instant.now());
        when(transactionUseCase.create(any(), any(), any(FinanceTransactionCommand.class))).thenReturn(saved);

        mockMvc.perform(post("/api/finance/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + saved.categoryId() + "\",\"transactionDate\":\"2026-08-01\",\"amount\":344000}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/finance/transactions/" + savedId));
    }

    @Test
    void create_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/finance/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + UUID.randomUUID() + "\",\"transactionDate\":\"2026-08-01\",\"amount\":344000}")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        FinanceTransaction updated = new FinanceTransaction(id, null, categoryId, USER_ID,
                LocalDate.of(2026, 8, 2), 500000L, "메모", Instant.now());
        when(transactionUseCase.update(any(), any(), any(FinanceTransactionCommand.class))).thenReturn(updated);

        mockMvc.perform(put("/api/finance/transactions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + categoryId + "\",\"transactionDate\":\"2026-08-02\",\"amount\":500000,\"memo\":\"메모\"}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(500000));
    }

    @Test
    void delete_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/finance/transactions/{id}", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());

        verify(transactionUseCase).delete(id, USER_ID);
    }

    @Test
    void share_returns200WithGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        FinanceTransaction shared = new FinanceTransaction(id, groupId, UUID.randomUUID(), USER_ID,
                LocalDate.of(2026, 8, 1), 344000L, null, Instant.now());
        when(transactionUseCase.shareToGroup(id, USER_ID)).thenReturn(shared);

        mockMvc.perform(patch("/api/finance/transactions/{id}/share", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()));
    }

    @Test
    void unshare_returns200WithNullGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        FinanceTransaction personal = new FinanceTransaction(id, null, UUID.randomUUID(), USER_ID,
                LocalDate.of(2026, 8, 1), 344000L, null, Instant.now());
        when(transactionUseCase.unshare(id, USER_ID)).thenReturn(personal);

        mockMvc.perform(patch("/api/finance/transactions/{id}/unshare", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()));
    }
}
