package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceBudget;
import com.kista.domain.model.finance.FinanceBudgetCommand;
import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.port.out.FinanceBudgetPort;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinanceBudgetService 단위 테스트")
class FinanceBudgetServiceTest {

    @Mock FinanceBudgetPort budgetPort;
    @Mock FinanceGroupPort financeGroupPort;
    @Mock FinanceCategoryPort financeCategoryPort;
    @InjectMocks FinanceBudgetService budgetService;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID budgetId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    private FinanceBudget existingBudget() {
        return new FinanceBudget(budgetId, groupId, categoryId, userId,
                LocalDate.of(2026, 1, 1), null, 500_000L, null);
    }

    private FinanceBudgetCommand command() {
        return new FinanceBudgetCommand(categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31), 300_000L);
    }

    // create/update는 저장 전에 categoryId 소유권·타입을 검증하므로, 그 경로를 타는 모든 테스트가
    // 이 그룹 소유의 비-ASSET(EXPENSE) 카테고리를 stub해야 한다.
    private FinanceCategory usableCategory() {
        return new FinanceCategory(categoryId, groupId, null, userId, FinanceCategory.Type.EXPENSE, "식비", 0, null);
    }

    @Test
    @DisplayName("list는 resolveGroupId로 얻은 groupId로 조회")
    void list_resolvesGroupId() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(budgetPort.findByGroupId(groupId, categoryId, null)).thenReturn(List.of(existingBudget()));

        List<FinanceBudget> result = budgetService.list(userId, null, categoryId, null);

        assertThat(result).hasSize(1);
        verify(financeGroupPort).resolveGroupId(userId, null);
    }

    @Test
    @DisplayName("create는 resolveGroupId 호출 후 예산 저장")
    void create_resolvesGroupIdThenSaves() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceBudget result = budgetService.create(userId, null, command());

        assertThat(result.groupId()).isEqualTo(groupId);
        assertThat(result.createdBy()).isEqualTo(userId);
        assertThat(result.amount()).isEqualTo(300_000L);
        verify(financeGroupPort).resolveGroupId(userId, null);
    }

    @Test
    @DisplayName("update는 load-then-verify-membership 패턴")
    void update_loadsThenVerifiesMembership() {
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(existingBudget());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceBudget result = budgetService.update(budgetId, userId, command());

        assertThat(result.amount()).isEqualTo(300_000L);
        assertThat(result.createdBy()).isEqualTo(userId); // 기존 createdBy 유지
        verify(financeGroupPort).resolveGroupId(userId, groupId);
    }

    @Test
    @DisplayName("delete는 load-then-verify-membership 후 하드 삭제(budgetPort.delete) 호출")
    void delete_callsHardDelete() {
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(existingBudget());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);

        budgetService.delete(budgetId, userId);

        verify(financeGroupPort).resolveGroupId(userId, groupId);
        verify(budgetPort).delete(budgetId); // 하드 삭제 — softDelete 계열 메서드가 포트에 없음
    }

    @Test
    @DisplayName("create 중 기간 중첩 시 OverlappingPeriodException이 그대로 전파됨")
    void create_overlappingPeriod_propagatesUntouched() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.save(any())).thenThrow(new FinanceBudget.OverlappingPeriodException("기간이 겹칩니다"));

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);
    }

    @Test
    @DisplayName("update 중 기간 중첩 시 OverlappingPeriodException이 그대로 전파됨")
    void update_overlappingPeriod_propagatesUntouched() {
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(existingBudget());
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.save(any())).thenThrow(new FinanceBudget.OverlappingPeriodException("기간이 겹칩니다"));

        assertThatThrownBy(() -> budgetService.update(budgetId, userId, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);
    }

    @Test
    @DisplayName("적용 종료일이 시작일보다 앞서면 IllegalArgumentException")
    void create_endDateBeforeStartDate_rejected() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        FinanceBudgetCommand invalid = new FinanceBudgetCommand(categoryId,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 1, 1), 300_000L);

        assertThatThrownBy(() -> budgetService.create(userId, null, invalid))
                .isInstanceOf(IllegalArgumentException.class);
        verify(budgetPort, never()).save(any());
    }

    @Test
    @DisplayName("ASSET 타입 카테고리에는 예산을 걸 수 없음")
    void create_assetTypeCategory_rejected() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory assetCategory = new FinanceCategory(categoryId, groupId, null, userId,
                FinanceCategory.Type.ASSET, "투자", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(assetCategory);

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(budgetPort, never()).save(any());
    }

    @Test
    @DisplayName("다른 그룹 소유 카테고리를 지정하면 SecurityException")
    void create_categoryFromOtherGroup_rejected() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory otherGroupCategory = new FinanceCategory(categoryId, UUID.randomUUID(), null, userId,
                FinanceCategory.Type.EXPENSE, "식비", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(otherGroupCategory);

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(SecurityException.class);
        verify(budgetPort, never()).save(any());
    }
}
