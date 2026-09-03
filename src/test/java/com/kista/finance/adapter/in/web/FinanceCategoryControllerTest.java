package com.kista.finance.adapter.in.web;

import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.model.FinanceCategoryCommand;
import com.kista.application.usecase.BlacklistUseCase;
import com.kista.finance.application.usecase.FinanceCategoryUseCase;
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

@WebMvcTest(FinanceCategoryController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceCategoryControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean FinanceCategoryUseCase categoryUseCase;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void list_returnsNestedTree() throws Exception {
        UUID l1Id = UUID.randomUUID();
        UUID l2Id = UUID.randomUUID();
        FinanceCategory l1 = new FinanceCategory(l1Id, null, null, null,
                FinanceCategory.Type.EXPENSE, "식비", 0, Instant.now());
        FinanceCategory l2 = new FinanceCategory(l2Id, null, l1Id, null,
                FinanceCategory.Type.EXPENSE, "외식", 0, Instant.now());
        when(categoryUseCase.list(any(), any(), any())).thenReturn(List.of(l1, l2));

        mockMvc.perform(get("/api/finance/categories")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(l1Id.toString()))
                .andExpect(jsonPath("$[0].children[0].id").value(l2Id.toString()))
                .andExpect(jsonPath("$[0].children[0].name").value("외식"));
    }

    @Test
    void list_returnsNestedTree_arbitraryDepth() throws Exception {
        UUID l1Id = UUID.randomUUID();
        UUID l2Id = UUID.randomUUID();
        UUID l3Id = UUID.randomUUID();
        FinanceCategory l1 = new FinanceCategory(l1Id, null, null, null,
                FinanceCategory.Type.EXPENSE, "식비", 0, Instant.now());
        FinanceCategory l2 = new FinanceCategory(l2Id, null, l1Id, null,
                FinanceCategory.Type.EXPENSE, "외식", 0, Instant.now());
        FinanceCategory l3 = new FinanceCategory(l3Id, null, l2Id, null,
                FinanceCategory.Type.EXPENSE, "배달", 0, Instant.now());
        when(categoryUseCase.list(any(), any(), any())).thenReturn(List.of(l1, l2, l3));

        mockMvc.perform(get("/api/finance/categories")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(l1Id.toString()))
                .andExpect(jsonPath("$[0].children[0].id").value(l2Id.toString()))
                .andExpect(jsonPath("$[0].children[0].children[0].id").value(l3Id.toString()))
                .andExpect(jsonPath("$[0].children[0].children[0].name").value("배달"));
    }

    @Test
    void create_returns201WithLocationHeader() throws Exception {
        UUID savedId = UUID.randomUUID();
        FinanceCategory saved = new FinanceCategory(savedId, null, null, USER_ID,
                FinanceCategory.Type.EXPENSE, "식비", 0, Instant.now());
        when(categoryUseCase.create(any(), any(), any(FinanceCategoryCommand.class))).thenReturn(saved);

        mockMvc.perform(post("/api/finance/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"name\":\"식비\",\"sortOrder\":0}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/finance/categories/" + savedId));
    }

    @Test
    void create_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/finance/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"EXPENSE\",\"name\":\"식비\",\"sortOrder\":0}")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void create_withoutType_returns400() throws Exception {
        mockMvc.perform(post("/api/finance/categories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"식비\",\"sortOrder\":0}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(categoryUseCase);
    }

    @Test
    void update_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        FinanceCategory updated = new FinanceCategory(id, null, null, USER_ID,
                FinanceCategory.Type.EXPENSE, "식비(수정)", 1, Instant.now());
        when(categoryUseCase.update(any(), any(), any(FinanceCategoryCommand.class))).thenReturn(updated);

        mockMvc.perform(put("/api/finance/categories/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"식비(수정)\",\"sortOrder\":1}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("식비(수정)"));
    }

    @Test
    void delete_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/finance/categories/{id}", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());

        verify(categoryUseCase).delete(id, USER_ID);
    }

    @Test
    void share_returns200WithGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        FinanceCategory shared = new FinanceCategory(id, groupId, null, USER_ID,
                FinanceCategory.Type.EXPENSE, "식비", 0, Instant.now());
        when(categoryUseCase.shareToGroup(id, USER_ID)).thenReturn(shared);

        mockMvc.perform(patch("/api/finance/categories/{id}/share", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()));
    }

    @Test
    void unshare_returns200WithNullGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        FinanceCategory personal = new FinanceCategory(id, null, null, USER_ID,
                FinanceCategory.Type.EXPENSE, "식비", 0, Instant.now());
        when(categoryUseCase.unshare(id, USER_ID)).thenReturn(personal);

        mockMvc.perform(patch("/api/finance/categories/{id}/unshare", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()));
    }
}
