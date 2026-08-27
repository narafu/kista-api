package com.kista.adapter.in.web;

import com.kista.domain.model.finance.AssetClass;
import com.kista.domain.model.finance.AssetSnapshot;
import com.kista.domain.model.finance.AssetSnapshotCommand;
import com.kista.domain.model.finance.FinanceAccount;
import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.model.finance.Market;
import com.kista.domain.port.in.AssetSnapshotUseCase;
import com.kista.domain.port.in.BlacklistUseCase;
import com.kista.domain.port.out.AppErrorLogPort;
import com.kista.domain.port.out.FinanceAccountPort;
import com.kista.domain.port.out.FinanceCategoryPort;
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

@WebMvcTest(AssetSnapshotController.class)
@Execution(ExecutionMode.SAME_THREAD)
class AssetSnapshotControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean JwtDecoder jwtDecoder;
    @MockitoBean BlacklistUseCase blacklistUseCase;
    @MockitoBean AssetSnapshotUseCase assetSnapshotUseCase;
    @MockitoBean FinanceCategoryPort financeCategoryPort;
    @MockitoBean FinanceAccountPort financeAccountPort;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static AssetSnapshot snapshot(UUID categoryId, UUID accountId) {
        return new AssetSnapshot(UUID.randomUUID(), null, categoryId, accountId, USER_ID,
                LocalDate.of(2026, 8, 1), AssetClass.EQUITY, Market.GLOBAL, "VR", null, 1_000_000L, Instant.now());
    }

    @Test
    void list_categoryIsL1_rootCategoryIdEqualsOwnId() throws Exception {
        UUID categoryId = UUID.randomUUID();
        when(assetSnapshotUseCase.list(any(), any(), any(), any(), any()))
                .thenReturn(List.of(snapshot(categoryId, null)));
        FinanceCategory l1Category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(l1Category);

        mockMvc.perform(get("/api/finance/asset-snapshots")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rootCategoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$[0].categoryName").value("주식"))
                .andExpect(jsonPath("$[0].accountName").value(nullValue()));
    }

    @Test
    void list_categoryIsL2_rootCategoryIdEqualsParentId() throws Exception {
        UUID parentId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(assetSnapshotUseCase.list(any(), any(), any(), any(), any()))
                .thenReturn(List.of(snapshot(categoryId, null)));
        FinanceCategory l2Category = new FinanceCategory(categoryId, null, parentId, null,
                FinanceCategory.Type.ASSET, "국내주식", 0, Instant.now());
        FinanceCategory rootCategory = new FinanceCategory(parentId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(l2Category);
        when(financeCategoryPort.findByIdOrThrow(parentId)).thenReturn(rootCategory);

        mockMvc.perform(get("/api/finance/asset-snapshots")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rootCategoryId").value(parentId.toString()))
                .andExpect(jsonPath("$[0].categoryName").value("국내주식"));
    }

    @Test
    void list_categoryIsL3_rootCategoryIdEqualsGrandparentId() throws Exception {
        UUID rootId = UUID.randomUUID();
        UUID parentId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(assetSnapshotUseCase.list(any(), any(), any(), any(), any()))
                .thenReturn(List.of(snapshot(categoryId, null)));
        FinanceCategory l3Category = new FinanceCategory(categoryId, null, parentId, null,
                FinanceCategory.Type.ASSET, "미국주식", 0, Instant.now());
        FinanceCategory l2Category = new FinanceCategory(parentId, null, rootId, null,
                FinanceCategory.Type.ASSET, "해외주식", 0, Instant.now());
        FinanceCategory rootCategory = new FinanceCategory(rootId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(l3Category);
        when(financeCategoryPort.findByIdOrThrow(parentId)).thenReturn(l2Category);
        when(financeCategoryPort.findByIdOrThrow(rootId)).thenReturn(rootCategory);

        mockMvc.perform(get("/api/finance/asset-snapshots")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rootCategoryId").value(rootId.toString()))
                .andExpect(jsonPath("$[0].categoryName").value("미국주식"));
    }

    @Test
    void list_withAccountId_accountNamePresent() throws Exception {
        UUID categoryId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        when(assetSnapshotUseCase.list(any(), any(), any(), any(), any()))
                .thenReturn(List.of(snapshot(categoryId, accountId)));
        FinanceCategory category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(category);
        FinanceAccount account = new FinanceAccount(accountId, null, USER_ID,
                FinanceAccount.Type.SECURITIES, "토스증권 일반계좌", null, null, Instant.now());
        when(financeAccountPort.findByIdOrThrow(accountId)).thenReturn(account);

        mockMvc.perform(get("/api/finance/asset-snapshots")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountName").value("토스증권 일반계좌"));
    }

    @Test
    void create_returns201WithLocationHeader() throws Exception {
        UUID categoryId = UUID.randomUUID();
        AssetSnapshot saved = snapshot(categoryId, null);
        when(assetSnapshotUseCase.create(any(), any(), any(AssetSnapshotCommand.class))).thenReturn(saved);
        FinanceCategory category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(category);

        mockMvc.perform(post("/api/finance/asset-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + categoryId + "\",\"entryDate\":\"2026-08-01\"," +
                                "\"assetClass\":\"EQUITY\",\"market\":\"GLOBAL\",\"amount\":1000000}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/finance/asset-snapshots/" + saved.id()));
    }

    @Test
    void create_withMemo_returnsMemoInResponse() throws Exception {
        UUID categoryId = UUID.randomUUID();
        AssetSnapshot saved = new AssetSnapshot(UUID.randomUUID(), null, categoryId, null, USER_ID,
                LocalDate.of(2026, 8, 1), AssetClass.EQUITY, Market.GLOBAL, "VR", "비상금 별도 관리", 1_000_000L, Instant.now());
        when(assetSnapshotUseCase.create(any(), any(), any(AssetSnapshotCommand.class))).thenReturn(saved);
        FinanceCategory category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(category);

        mockMvc.perform(post("/api/finance/asset-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + categoryId + "\",\"entryDate\":\"2026-08-01\"," +
                                "\"assetClass\":\"EQUITY\",\"market\":\"GLOBAL\",\"memo\":\"비상금 별도 관리\",\"amount\":1000000}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memo").value("비상금 별도 관리"));

        verify(assetSnapshotUseCase).create(eq(USER_ID), any(), argThat(cmd -> "비상금 별도 관리".equals(cmd.memo())));
    }

    @Test
    void create_withoutCsrf_returns403() throws Exception {
        mockMvc.perform(post("/api/finance/asset-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + UUID.randomUUID() + "\",\"entryDate\":\"2026-08-01\"," +
                                "\"assetClass\":\"EQUITY\",\"market\":\"GLOBAL\",\"amount\":1000000}")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isForbidden());
    }

    @Test
    void delete_returns204() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(delete("/api/finance/asset-snapshots/{id}", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isNoContent());

        verify(assetSnapshotUseCase).delete(id, USER_ID);
    }

    @Test
    void share_returns200WithGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        AssetSnapshot shared = new AssetSnapshot(id, groupId, categoryId, null, USER_ID,
                LocalDate.of(2026, 8, 1), AssetClass.EQUITY, Market.GLOBAL, "VR", null, 1_000_000L, Instant.now());
        when(assetSnapshotUseCase.shareToGroup(id, USER_ID)).thenReturn(shared);
        FinanceCategory category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(category);

        mockMvc.perform(patch("/api/finance/asset-snapshots/{id}/share", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()));
    }

    @Test
    void unshare_returns200WithNullGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        AssetSnapshot personal = new AssetSnapshot(id, null, categoryId, null, USER_ID,
                LocalDate.of(2026, 8, 1), AssetClass.EQUITY, Market.GLOBAL, "VR", null, 1_000_000L, Instant.now());
        when(assetSnapshotUseCase.unshare(id, USER_ID)).thenReturn(personal);
        FinanceCategory category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(category);

        mockMvc.perform(patch("/api/finance/asset-snapshots/{id}/unshare", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()));
    }
}
