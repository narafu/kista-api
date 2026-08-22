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
import java.util.Optional;
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

    // 개인 소유(groupId=null) 예산 — 신규 등록은 항상 이 형태로 저장된다.
    private FinanceBudget personalBudget() {
        return new FinanceBudget(budgetId, null, categoryId, userId,
                LocalDate.of(2026, 1, 1), null, 500_000L, null);
    }

    private FinanceBudgetCommand command() {
        return new FinanceBudgetCommand(categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31), 300_000L);
    }

    // create/update는 저장 전에 categoryId 소유권·타입을 검증하므로, 그 경로를 타는 모든 테스트가
    // 접근 가능한 비-ASSET(EXPENSE) 카테고리를 stub해야 한다.
    private FinanceCategory usableCategory() {
        return new FinanceCategory(categoryId, null, null, userId, FinanceCategory.Type.EXPENSE, "식비", 0, null);
    }

    @Test
    @DisplayName("list는 findCurrentGroupId로 얻은 currentGroupId로 조회")
    void list_queriesWithCurrentGroupId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(budgetPort.findMyScope(userId, groupId, categoryId, null)).thenReturn(List.of(personalBudget()));

        List<FinanceBudget> result = budgetService.list(userId, null, categoryId, null);

        assertThat(result).hasSize(1);
        verify(budgetPort).findMyScope(userId, groupId, categoryId, null);
    }

    // 신규 등록은 requestedGroupId와 무관하게 항상 개인 소유(groupId=null)로 저장된다.
    @Test
    @DisplayName("create는 requestedGroupId를 무시하고 개인 소유(groupId=null)로 저장")
    void create_alwaysSavesAsPersonalOwnership() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceBudget result = budgetService.create(userId, groupId, command());

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.amount()).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("update는 load-then-verify 패턴 — 기존 groupId 기준으로 접근 검증")
    void update_loadsThenVerifiesAccess() {
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(personalBudget());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceBudget result = budgetService.update(budgetId, userId, command());

        assertThat(result.amount()).isEqualTo(300_000L);
        assertThat(result.userId()).isEqualTo(userId); // 기존 소유자 유지
        assertThat(result.groupId()).isNull(); // 기존 groupId 유지 — 업데이트로 그룹 전환되지 않음
    }

    @Test
    @DisplayName("update 시 접근 불가한 예산이면 SecurityException")
    void update_notAccessible_throwsSecurityException() {
        FinanceBudget othersPersonalBudget = new FinanceBudget(budgetId, null, categoryId, UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), null, 500_000L, null);
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(othersPersonalBudget);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> budgetService.update(budgetId, userId, command()))
                .isInstanceOf(SecurityException.class);

        verify(budgetPort, never()).save(any());
    }

    @Test
    @DisplayName("delete는 load-then-verify 후 하드 삭제(budgetPort.delete) 호출")
    void delete_callsHardDelete() {
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(personalBudget());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        budgetService.delete(budgetId, userId);

        verify(budgetPort).delete(budgetId); // 하드 삭제 — softDelete 계열 메서드가 포트에 없음
    }

    @Test
    @DisplayName("create 중 기간 중첩 시 OverlappingPeriodException이 그대로 전파됨")
    void create_overlappingPeriod_propagatesUntouched() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.save(any())).thenThrow(new FinanceBudget.OverlappingPeriodException("기간이 겹칩니다"));

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);
    }

    @Test
    @DisplayName("적용 종료일이 시작일보다 앞서면 IllegalArgumentException")
    void create_endDateBeforeStartDate_rejected() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
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
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceCategory assetCategory = new FinanceCategory(categoryId, null, null, userId,
                FinanceCategory.Type.ASSET, "투자", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(assetCategory);

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(budgetPort, never()).save(any());
    }

    @Test
    @DisplayName("접근 불가한 카테고리를 지정하면 SecurityException")
    void create_inaccessibleCategory_rejected() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceCategory othersPersonalCategory = new FinanceCategory(categoryId, null, null, UUID.randomUUID(),
                FinanceCategory.Type.EXPENSE, "식비", 0, null);
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(othersPersonalCategory);

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(SecurityException.class);
        verify(budgetPort, never()).save(any());
    }

    // ----- shareToGroup -----

    @Test
    @DisplayName("shareToGroup은 본인 소유 개인 예산을 현재 그룹으로 전환")
    void shareToGroup_ownedPersonalBudget_movesToCurrentGroup() {
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(personalBudget());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceBudget result = budgetService.shareToGroup(budgetId, userId);

        assertThat(result.groupId()).isEqualTo(groupId);
    }

    @Test
    @DisplayName("shareToGroup은 본인 소유가 아니면 SecurityException")
    void shareToGroup_notOwner_throwsSecurityException() {
        FinanceBudget othersBudget = new FinanceBudget(budgetId, null, categoryId, UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), null, 500_000L, null);
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(othersBudget);

        assertThatThrownBy(() -> budgetService.shareToGroup(budgetId, userId))
                .isInstanceOf(SecurityException.class);

        verify(budgetPort, never()).save(any());
    }

    @Test
    @DisplayName("shareToGroup은 무그룹 유저면 IllegalStateException")
    void shareToGroup_noCurrentGroup_throwsIllegalState() {
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(personalBudget());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> budgetService.shareToGroup(budgetId, userId))
                .isInstanceOf(IllegalStateException.class);

        verify(budgetPort, never()).save(any());
    }

    @Test
    @DisplayName("shareToGroup은 이미 다른 그룹에 공유돼 있으면 IllegalStateException")
    void shareToGroup_alreadySharedToAnotherGroup_throwsIllegalState() {
        FinanceBudget alreadyShared = new FinanceBudget(budgetId, UUID.randomUUID(), categoryId, userId,
                LocalDate.of(2026, 1, 1), null, 500_000L, null);
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(alreadyShared);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        assertThatThrownBy(() -> budgetService.shareToGroup(budgetId, userId))
                .isInstanceOf(IllegalStateException.class);

        verify(budgetPort, never()).save(any());
    }

    // ----- unshare -----

    @Test
    @DisplayName("unshare는 그룹 공유 예산을 개인 소유로 되돌리고 소유자는 유지")
    void unshare_groupSharedBudget_movesToPersonalKeepingOwner() {
        UUID ownerId = UUID.randomUUID();
        FinanceBudget sharedBudget = new FinanceBudget(budgetId, groupId, categoryId, ownerId,
                LocalDate.of(2026, 1, 1), null, 500_000L, null);
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(sharedBudget);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceBudget result = budgetService.unshare(budgetId, userId);

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(ownerId);
    }

    @Test
    @DisplayName("unshare는 같은 그룹 소속이면 소유자가 아니어도 허용")
    void unshare_nonOwnerSameGroupMember_allowed() {
        UUID ownerId = UUID.randomUUID();
        FinanceBudget sharedBudget = new FinanceBudget(budgetId, groupId, categoryId, ownerId,
                LocalDate.of(2026, 1, 1), null, 500_000L, null);
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(sharedBudget);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceBudget result = budgetService.unshare(budgetId, userId);

        assertThat(result.groupId()).isNull();
    }

    @Test
    @DisplayName("unshare는 소유자도 아니고 같은 그룹도 아니면 SecurityException")
    void unshare_notOwnerAndNotSameGroup_throwsSecurityException() {
        FinanceBudget sharedBudget = new FinanceBudget(budgetId, UUID.randomUUID(), categoryId, UUID.randomUUID(),
                LocalDate.of(2026, 1, 1), null, 500_000L, null);
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(sharedBudget);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        assertThatThrownBy(() -> budgetService.unshare(budgetId, userId))
                .isInstanceOf(SecurityException.class);

        verify(budgetPort, never()).save(any());
    }

    @Test
    @DisplayName("unshare는 이미 개인 소유면 그대로 반환(멱등)")
    void unshare_alreadyPersonal_isIdempotent() {
        when(budgetPort.findByIdOrThrow(budgetId)).thenReturn(personalBudget());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        FinanceBudget result = budgetService.unshare(budgetId, userId);

        assertThat(result.groupId()).isNull();
        verify(budgetPort, never()).save(any());
    }
}
