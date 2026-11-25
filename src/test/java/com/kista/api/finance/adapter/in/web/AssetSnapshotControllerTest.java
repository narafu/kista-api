package com.kista.api.finance.adapter.in.web;

import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.AssetSnapshot;
import com.kista.api.finance.domain.model.AssetSnapshotCommand;
import com.kista.api.finance.domain.model.FinanceAccount;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.Market;
import com.kista.api.finance.application.service.AssetSnapshotService;
import com.kista.api.admin.application.port.output.AppErrorLogPort;
import com.kista.api.finance.application.port.output.FinanceAccountPort;
import com.kista.api.finance.application.port.output.FinanceCategoryPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.kista.api.finance.domain.model.AssetSnapshotAggregates.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static com.kista.shared.support.WebMvcTestSupport.*;
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
    @MockitoBean AssetSnapshotService assetSnapshotService;
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
        when(assetSnapshotService.list(any(), any(), any(), any()))
                .thenReturn(List.of(snapshot(categoryId, null)));
        FinanceCategory l1Category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(l1Category);

        mockMvc.perform(get("/api/finance/asset-snapshots")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].rootCategoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$[0].categoryName").value("주식"))
                .andExpect(jsonPath("$[0].accountName").value(nullValue()))
                .andExpect(jsonPath("$[0].accountInstitution").value(nullValue()))
                .andExpect(jsonPath("$[0].accountOwner").value(nullValue()));
    }

    @Test
    void list_categoryIsL2_rootCategoryIdEqualsParentId() throws Exception {
        UUID parentId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        when(assetSnapshotService.list(any(), any(), any(), any()))
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
        when(assetSnapshotService.list(any(), any(), any(), any()))
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
        when(assetSnapshotService.list(any(), any(), any(), any()))
                .thenReturn(List.of(snapshot(categoryId, accountId)));
        FinanceCategory category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(category);
        FinanceAccount account = new FinanceAccount(accountId, null, USER_ID,
                FinanceAccount.Type.SECURITIES, "토스증권 일반계좌", null, null, "토스증권", "본인", Instant.now());
        when(financeAccountPort.findByIdOrThrow(accountId)).thenReturn(account);

        mockMvc.perform(get("/api/finance/asset-snapshots")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountName").value("토스증권 일반계좌"))
                .andExpect(jsonPath("$[0].accountInstitution").value("토스증권"))
                .andExpect(jsonPath("$[0].accountOwner").value("본인"));
    }

    @Test
    void create_returns201WithLocationHeader() throws Exception {
        UUID categoryId = UUID.randomUUID();
        AssetSnapshot saved = snapshot(categoryId, null);
        when(assetSnapshotService.create(any(), anyBoolean(), any(AssetSnapshotCommand.class))).thenReturn(saved);
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
        when(assetSnapshotService.create(any(), anyBoolean(), any(AssetSnapshotCommand.class))).thenReturn(saved);
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

        verify(assetSnapshotService).create(eq(USER_ID), eq(false), argThat(cmd -> "비상금 별도 관리".equals(cmd.memo())));
    }

    @Test
    void create_withShareToGroupParam_passesFlagToUseCase() throws Exception {
        UUID categoryId = UUID.randomUUID();
        AssetSnapshot saved = snapshot(categoryId, null);
        when(assetSnapshotService.create(any(), anyBoolean(), any(AssetSnapshotCommand.class))).thenReturn(saved);
        FinanceCategory category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(category);

        mockMvc.perform(post("/api/finance/asset-snapshots")
                        .param("shareToGroup", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + categoryId + "\",\"entryDate\":\"2026-08-01\"," +
                                "\"assetClass\":\"EQUITY\",\"market\":\"GLOBAL\",\"amount\":1000000}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated());

        verify(assetSnapshotService).create(eq(USER_ID), eq(true), any(AssetSnapshotCommand.class));
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

        verify(assetSnapshotService).delete(id, USER_ID);
    }

    @Test
    void share_returns200WithGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        AssetSnapshot shared = new AssetSnapshot(id, groupId, categoryId, null, USER_ID,
                LocalDate.of(2026, 8, 1), AssetClass.EQUITY, Market.GLOBAL, "VR", null, 1_000_000L, Instant.now());
        when(assetSnapshotService.shareToGroup(id, USER_ID)).thenReturn(shared);
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
        when(assetSnapshotService.unshare(id, USER_ID)).thenReturn(personal);
        FinanceCategory category = new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.ASSET, "주식", 0, Instant.now());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(category);

        mockMvc.perform(patch("/api/finance/asset-snapshots/{id}/unshare", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()));
    }

    @Test
    void summary_returns200WithYearMonthStrings() throws Exception {
        YearMonth aug = YearMonth.of(2026, 8);
        MonthlyAssetSummary current = new MonthlyAssetSummary(aug, 7L, 10L, 3L,
                new AssetClassAmount(AssetClass.EQUITY, 10L), 2,
                List.of(new CategoryAmount(FinanceCategory.SYSTEM_LOAN_ID, 3L)),
                List.of(new AssetClassAmount(AssetClass.EQUITY, 10L)));
        when(assetSnapshotService.summary(USER_ID, aug))
                .thenReturn(new AssetSummaryResult(aug, List.of(aug), current, null));

        mockMvc.perform(get("/api/finance/asset-snapshots/summary").param("month", "2026-08")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value("2026-08"))
                .andExpect(jsonPath("$.availableMonths[0]").value("2026-08"))
                .andExpect(jsonPath("$.current.netWorth").value(7))
                .andExpect(jsonPath("$.current.largestAssetClass.assetClass").value("EQUITY"))
                .andExpect(jsonPath("$.previous").value(nullValue()));
    }

    @Test
    void summary_badMonth_returns400() throws Exception {
        mockMvc.perform(get("/api/finance/asset-snapshots/summary").param("month", "2026-13")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trend_returns200() throws Exception {
        YearMonth aug = YearMonth.of(2026, 8);
        when(assetSnapshotService.trend(USER_ID, 6, null)).thenReturn(List.of(
                new TrendPoint(aug, 7L, 10L, List.of(new CategoryAmount(FinanceCategory.SYSTEM_LOAN_ID, 3L)),
                        List.of(new AssetClassAmount(AssetClass.CASH, 0L)))));

        mockMvc.perform(get("/api/finance/asset-snapshots/trend").param("months", "6")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].month").value("2026-08"))
                .andExpect(jsonPath("$.points[0].byAssetClass[0].assetClass").value("CASH"));
    }

    @Test
    void trend_badMonths_returns400() throws Exception {
        mockMvc.perform(get("/api/finance/asset-snapshots/trend").param("months", "abc")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void composition_returns200() throws Exception {
        YearMonth aug = YearMonth.of(2026, 8);
        when(assetSnapshotService.composition(USER_ID, Basis.ASSET_CLASS, null, null)).thenReturn(List.of(
                new CompositionColumn(aug, 100L, List.of(new CompositionEntry("EQUITY", 100L, new BigDecimal("100.00"))))));

        mockMvc.perform(get("/api/finance/asset-snapshots/composition").param("basis", "ASSET_CLASS")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[0].month").value("2026-08"))
                .andExpect(jsonPath("$.columns[0].entries[0].item").value("EQUITY"))
                .andExpect(jsonPath("$.columns[0].entries[0].percent").value(100.0));
    }

    @Test
    void composition_badBasis_returns400() throws Exception {
        mockMvc.perform(get("/api/finance/asset-snapshots/composition").param("basis", "NOPE")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void check_returns200() throws Exception {
        YearMonth aug = YearMonth.of(2026, 8);
        when(assetSnapshotService.check(USER_ID, aug)).thenReturn(new AssetCheckResult(aug, YearMonth.of(2026, 7),
                List.of(FinanceCategory.SYSTEM_LOAN_ID),
                List.of(new MissingAccount(null, "투자", AssetClass.EQUITY)),
                List.of(new DateGroup(LocalDate.of(2026, 8, 1), 2))));

        mockMvc.perform(get("/api/finance/asset-snapshots/check").param("month", "2026-08")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousMonth").value("2026-07"))
                .andExpect(jsonPath("$.missingAccounts[0].accountName").value(nullValue()))
                .andExpect(jsonPath("$.dateGroups[0].date").value("2026-08-01"))
                .andExpect(jsonPath("$.dateGroups[0].count").value(2));
    }

    @Test
    void check_missingMonth_returns400() throws Exception {
        mockMvc.perform(get("/api/finance/asset-snapshots/check")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }
}
