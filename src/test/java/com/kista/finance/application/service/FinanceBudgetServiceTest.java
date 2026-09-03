package com.kista.finance.application.service;

import com.kista.finance.domain.model.FinanceBudget;
import com.kista.finance.domain.model.FinanceBudgetCommand;
import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.port.out.FinanceBudgetPort;
import com.kista.finance.domain.port.out.FinanceCategoryPort;
import com.kista.finance.domain.port.out.FinanceGroupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    // ----- create: 겹침 자동조정 -----

    private FinanceBudget budgetOf(LocalDate start, LocalDate end) {
        return new FinanceBudget(UUID.randomUUID(), null, categoryId, userId, start, end, 500_000L, null);
    }

    @Test
    @DisplayName("create: 기존이 새 예산 시작일 이전부터 시작해 시작일 이후로 걸치면 기존 종료일을 트림")
    void create_trimsExistingEndDate_whenExistingStartsBeforeNewStart() {
        FinanceBudget existing = budgetOf(LocalDate.of(2020, 1, 1), null); // 무기한
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(existing));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        budgetService.create(userId, null, command()); // command() = 2026/06/01~2026/12/31

        ArgumentCaptor<FinanceBudget> captor = ArgumentCaptor.forClass(FinanceBudget.class);
        verify(budgetPort, times(2)).save(captor.capture()); // 1) 트림된 기존 2) 신규
        FinanceBudget trimmed = captor.getAllValues().get(0);
        assertThat(trimmed.id()).isEqualTo(existing.id());
        assertThat(trimmed.applyEndDate()).isEqualTo(LocalDate.of(2026, 5, 31));
        verify(budgetPort, never()).delete(any());
    }

    @Test
    @DisplayName("create: 기존이 새 예산 범위 안에 완전히 들어가면(뒷부분 흡수) 기존을 삭제")
    void create_deletesExisting_whenFullyAbsorbedByNewRange() {
        FinanceBudget existing = budgetOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(existing));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        budgetService.create(userId, null, command());

        verify(budgetPort).delete(existing.id());
        verify(budgetPort, times(1)).save(any()); // 신규만 저장, 트림 없음
    }

    @Test
    @DisplayName("create: 기존이 새 예산 앞뒤로 모두 걸치면(중간에 낌) 409")
    void create_rejects_whenExistingWrapsAroundNewRange() {
        FinanceBudget existing = budgetOf(LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);

        verify(budgetPort, never()).save(any());
        verify(budgetPort, never()).delete(any());
    }

    @Test
    @DisplayName("create: 기존이 새 예산 시작일 이후 시작해 종료일 뒤로도 이어지면(역트림 필요) 409")
    void create_rejects_whenExistingExtendsPastNewEnd() {
        FinanceBudget existing = budgetOf(LocalDate.of(2026, 9, 1), null); // 무기한
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);

        verify(budgetPort, never()).save(any());
        verify(budgetPort, never()).delete(any());
    }

    @Test
    @DisplayName("create: 트림 대상과 삭제 대상이 함께 있으면 둘 다 처리")
    void create_handlesMixedTrimAndDeleteCandidates() {
        FinanceBudget toTrim = budgetOf(LocalDate.of(2020, 1, 1), null);
        FinanceBudget toDelete = budgetOf(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(toTrim, toDelete));
        when(budgetPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        budgetService.create(userId, null, command());

        verify(budgetPort).delete(toDelete.id());
        ArgumentCaptor<FinanceBudget> captor = ArgumentCaptor.forClass(FinanceBudget.class);
        verify(budgetPort, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).id()).isEqualTo(toTrim.id());
        assertThat(captor.getAllValues().get(0).applyEndDate()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    @Test
    @DisplayName("create: 거부 후보가 하나라도 있으면 다른 정상 후보도 변경/삭제되지 않음")
    void create_rejectsWithoutPartialMutation_whenAnyCandidateRejected() {
        FinanceBudget validTrim = budgetOf(LocalDate.of(2020, 1, 1), null);
        FinanceBudget wrapsAround = budgetOf(LocalDate.of(2020, 1, 1), LocalDate.of(2027, 12, 31));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeCategoryPort.findByIdOrThrow(categoryId)).thenReturn(usableCategory());
        when(budgetPort.findOverlapping(userId, categoryId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31)))
                .thenReturn(List.of(validTrim, wrapsAround));

        assertThatThrownBy(() -> budgetService.create(userId, null, command()))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);

        verify(budgetPort, never()).save(any());
        verify(budgetPort, never()).delete(any());
    }
}
