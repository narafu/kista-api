package com.kista.api.finance.adapter.in.web;

import com.kista.api.finance.domain.model.BudgetProgress;
import com.kista.api.finance.domain.model.FinanceCategory;
import com.kista.api.finance.domain.model.FinanceTransaction;
import com.kista.api.finance.domain.model.FlowPeriod;
import com.kista.api.finance.domain.model.FlowSummary;
import com.kista.api.finance.domain.model.FlowTrendPoint;
import com.kista.api.finance.domain.model.FinanceTransactionCommand;
import com.kista.api.finance.application.service.FinanceTransactionService;
import com.kista.api.admin.application.port.output.AppErrorLogPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

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

@WebMvcTest(FinanceTransactionController.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceTransactionControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean AppErrorLogPort appErrorLogPort;
    @MockitoBean FinanceTransactionService financeTransactionService;

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void list_withNoQueryParams_returns200() throws Exception {
        when(financeTransactionService.list(any(), any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/finance/transactions")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void list_withAllQueryParams_returns200() throws Exception {
        when(financeTransactionService.list(any(), any(), any(), any(), any())).thenReturn(List.of());

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
        when(financeTransactionService.create(any(), anyBoolean(), any(FinanceTransactionCommand.class))).thenReturn(saved);

        mockMvc.perform(post("/api/finance/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + saved.categoryId() + "\",\"transactionDate\":\"2026-08-01\",\"amount\":344000}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/finance/transactions/" + savedId));

        // shareToGroup 미지정 시 기본 false로 서비스 호출
        verify(financeTransactionService).create(eq(USER_ID), eq(false), any(FinanceTransactionCommand.class));
    }

    @Test
    void create_withShareToGroupParam_passesTrueToUsecase() throws Exception {
        UUID savedId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        FinanceTransaction saved = new FinanceTransaction(savedId, groupId, UUID.randomUUID(), USER_ID,
                LocalDate.of(2026, 8, 1), 344000L, null, Instant.now());
        when(financeTransactionService.create(any(), anyBoolean(), any(FinanceTransactionCommand.class))).thenReturn(saved);

        mockMvc.perform(post("/api/finance/transactions")
                        .param("shareToGroup", "true")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + saved.categoryId() + "\",\"transactionDate\":\"2026-08-01\",\"amount\":344000}")
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()));

        verify(financeTransactionService).create(eq(USER_ID), eq(true), any(FinanceTransactionCommand.class));
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
        when(financeTransactionService.update(any(), any(), any(FinanceTransactionCommand.class))).thenReturn(updated);

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

        verify(financeTransactionService).delete(id, USER_ID);
    }

    @Test
    void share_returns200WithGroupId() throws Exception {
        UUID id = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        FinanceTransaction shared = new FinanceTransaction(id, groupId, UUID.randomUUID(), USER_ID,
                LocalDate.of(2026, 8, 1), 344000L, null, Instant.now());
        when(financeTransactionService.shareToGroup(id, USER_ID)).thenReturn(shared);

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
        when(financeTransactionService.unshare(id, USER_ID)).thenReturn(personal);

        mockMvc.perform(patch("/api/finance/transactions/{id}/unshare", id)
                        .with(csrf()).with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupId").value(nullValue()));
    }

    @Test
    void summary_returns200WithShape() throws Exception {
        when(financeTransactionService.summary(eq(USER_ID), eq(FinanceCategory.Type.EXPENSE),
                eq(new FlowPeriod(YearMonth.of(2026, 8), FlowPeriod.Mode.MONTHLY))))
                .thenReturn(new FlowSummary(3_500_000L, 12, 3_000_000L, null, 3_200_000L, null, new BigDecimal("0.4521")));

        mockMvc.perform(get("/api/finance/transactions/summary")
                        .param("type", "EXPENSE").param("mode", "MONTHLY").param("month", "2026-08")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3_500_000))
                .andExpect(jsonPath("$.count").value(12))
                .andExpect(jsonPath("$.previousTotal").value(3_000_000))
                .andExpect(jsonPath("$.previousYearTotal").value(nullValue()))
                .andExpect(jsonPath("$.yearlyAverage").value(3_200_000))
                .andExpect(jsonPath("$.remaining").value(nullValue()))
                .andExpect(jsonPath("$.incomeRatio").value(0.4521));
    }

    @Test
    void summary_withAssetType_returns400() throws Exception {
        when(financeTransactionService.summary(any(), eq(FinanceCategory.Type.ASSET), any()))
                .thenThrow(new IllegalArgumentException("자산 유형은 거래 집계에 사용할 수 없습니다."));

        mockMvc.perform(get("/api/finance/transactions/summary")
                        .param("type", "ASSET").param("mode", "MONTHLY").param("month", "2026-08")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trend_returns200WithMonthLabel() throws Exception {
        UUID rootId = UUID.randomUUID();
        when(financeTransactionService.trend(eq(USER_ID), eq(FinanceCategory.Type.EXPENSE),
                eq(new FlowPeriod(YearMonth.of(2026, 8), FlowPeriod.Mode.MONTHLY)), eq(2)))
                .thenReturn(List.of(new FlowTrendPoint("2026-08", 2000L, List.of(new FlowTrendPoint.RootAmount(rootId, 2000L)))));

        mockMvc.perform(get("/api/finance/transactions/trend")
                        .param("type", "EXPENSE").param("mode", "MONTHLY").param("month", "2026-08").param("range", "2")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points[0].period").value("2026-08"))
                .andExpect(jsonPath("$.points[0].amount").value(2000))
                .andExpect(jsonPath("$.points[0].byCategory[0].categoryId").value(rootId.toString()))
                .andExpect(jsonPath("$.points[0].byCategory[0].amount").value(2000));
    }

    @Test
    void trend_withRangeOver120_returns400() throws Exception {
        when(financeTransactionService.trend(eq(USER_ID), eq(FinanceCategory.Type.EXPENSE), any(), eq(121)))
                .thenThrow(new IllegalArgumentException("조회 기간은 1 이상 120 이하여야 합니다."));

        mockMvc.perform(get("/api/finance/transactions/trend")
                        .param("type", "EXPENSE").param("mode", "MONTHLY").param("month", "2026-08").param("range", "121")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void trend_withInvalidMonth_returns400() throws Exception {
        mockMvc.perform(get("/api/finance/transactions/trend")
                        .param("type", "EXPENSE").param("mode", "MONTHLY").param("month", "2026-13")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(financeTransactionService);
    }

    @Test
    void budgetProgress_returns200WithTree() throws Exception {
        UUID categoryId = UUID.randomUUID();
        UUID budgetId = UUID.randomUUID();
        var node = new BudgetProgress.Node(categoryId, "식비", 0, false, budgetId, 1200L, 100L, 1100L,
                new BigDecimal("0.0833"), List.of());
        when(financeTransactionService.budgetProgress(eq(USER_ID), eq(FinanceCategory.Type.EXPENSE),
                eq(new FlowPeriod(YearMonth.of(2025, 3), FlowPeriod.Mode.YEARLY))))
                .thenReturn(new BudgetProgress(List.of(node), List.of(new BudgetProgress.Unbudgeted(UUID.randomUUID(), "교통", 35L))));

        mockMvc.perform(get("/api/finance/transactions/budget-progress")
                        .param("type", "EXPENSE").param("mode", "YEARLY").param("month", "2025-03")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes[0].categoryId").value(categoryId.toString()))
                .andExpect(jsonPath("$.nodes[0].isGroup").value(false))
                .andExpect(jsonPath("$.nodes[0].budgetId").value(budgetId.toString()))
                .andExpect(jsonPath("$.nodes[0].usageRatio").value(0.0833))
                .andExpect(jsonPath("$.nodes[0].children").isArray())
                .andExpect(jsonPath("$.unbudgeted[0].categoryName").value("교통"));
    }

    @Test
    void budgetProgress_withUnknownMode_returns400() throws Exception {
        mockMvc.perform(get("/api/finance/transactions/budget-progress")
                        .param("type", "EXPENSE").param("mode", "WEEKLY").param("month", "2026-08")
                        .with(authentication(userToken(USER_ID))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(financeTransactionService);
    }
}
