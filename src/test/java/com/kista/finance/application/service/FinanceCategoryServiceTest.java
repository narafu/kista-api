package com.kista.finance.application.service;

import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.model.FinanceCategoryCommand;
import com.kista.finance.domain.port.out.FinanceCategoryPort;
import com.kista.finance.domain.port.out.FinanceGroupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinanceCategoryService 단위 테스트")
class FinanceCategoryServiceTest {

    @Mock FinanceCategoryPort categoryPort;
    @Mock FinanceGroupPort financeGroupPort;
    @InjectMocks FinanceCategoryService categoryService;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();
    private final UUID categoryId = UUID.randomUUID();

    private FinanceCategory systemCategory() {
        return new FinanceCategory(categoryId, null, null, null,
                FinanceCategory.Type.INCOME, "근로소득", 10, null);
    }

    private FinanceCategory groupCategory(UUID ownerGroupId) {
        return new FinanceCategory(categoryId, ownerGroupId, null, userId,
                FinanceCategory.Type.EXPENSE, "식비", 10, null);
    }

    private FinanceCategory personalCategory() {
        return new FinanceCategory(categoryId, null, null, userId,
                FinanceCategory.Type.EXPENSE, "개인카테고리", 10, null);
    }

    @Test
    @DisplayName("시스템 카테고리 수정 시 SecurityException — isSystem 체크가 그룹 조회보다 먼저 실행됨")
    void update_systemCategory_throwsSecurityException_beforeGroupLookup() {
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(systemCategory());

        assertThatThrownBy(() -> categoryService.update(categoryId, userId,
                new FinanceCategoryCommand(null, FinanceCategory.Type.INCOME, "변경명", 20)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("시스템");

        verify(financeGroupPort, never()).findCurrentGroupId(any());
        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("시스템 카테고리 삭제 시 SecurityException — isSystem 체크가 그룹 조회보다 먼저 실행됨")
    void delete_systemCategory_throwsSecurityException_beforeGroupLookup() {
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(systemCategory());

        assertThatThrownBy(() -> categoryService.delete(categoryId, userId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("시스템");

        verify(financeGroupPort, never()).findCurrentGroupId(any());
        verify(categoryPort, never()).softDeleteWithChildren(any());
    }

    @Test
    @DisplayName("생성 시 parentId가 타 그룹 소유 카테고리를 가리키면 IllegalArgumentException")
    void create_parentBelongsToDifferentGroup_throws() {
        UUID otherGroupId = UUID.randomUUID();
        UUID otherOwnerId = UUID.randomUUID(); // 부모를 소유하지 않아야 owned=false로 verifyAccessibleBy가 거부한다
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        FinanceCategory otherGroupParent = new FinanceCategory(UUID.randomUUID(), otherGroupId, null, otherOwnerId,
                FinanceCategory.Type.EXPENSE, "타그룹부모", 10, null);
        when(categoryPort.findByIdOrThrow(otherGroupParent.id())).thenReturn(otherGroupParent);

        FinanceCategoryCommand command = new FinanceCategoryCommand(
                otherGroupParent.id(), FinanceCategory.Type.EXPENSE, "새카테고리", 10);

        assertThatThrownBy(() -> categoryService.create(userId, null, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("다른 그룹");

        verify(categoryPort, never()).save(any());
    }

    // 회귀(플랜 항목 8): 부모로 개인 카테고리(groupId=null, userId 있음)를 지정해도 verifyAccessibleBy 내부에서
    // NPE 없이 정상 판정돼야 한다 — 본인 소유면 통과, 타인 소유면 IllegalArgumentException으로 거부.
    @Test
    @DisplayName("생성 시 parentId가 본인의 개인 카테고리를 가리키면 NPE 없이 정상 생성됨")
    void create_parentIsOwnPersonalCategory_noNpe_succeeds() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceCategory personalParent = personalCategory();
        when(categoryPort.findByIdOrThrow(personalParent.id())).thenReturn(personalParent);
        FinanceCategory saved = new FinanceCategory(UUID.randomUUID(), null, personalParent.id(), userId,
                FinanceCategory.Type.EXPENSE, "새카테고리", 10, null);
        when(categoryPort.save(any())).thenReturn(saved);

        FinanceCategoryCommand command = new FinanceCategoryCommand(
                personalParent.id(), FinanceCategory.Type.EXPENSE, "새카테고리", 10);

        assertThat(categoryService.create(userId, null, command)).isEqualTo(saved);
        verify(categoryPort).save(any());
    }

    @Test
    @DisplayName("생성 시 parentId가 타인의 개인 카테고리를 가리키면 NPE 없이 IllegalArgumentException으로 거부됨")
    void create_parentIsOthersPersonalCategory_noNpe_rejected() {
        UUID otherUserId = UUID.randomUUID();
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        FinanceCategory othersPersonalParent = new FinanceCategory(UUID.randomUUID(), null, null, otherUserId,
                FinanceCategory.Type.EXPENSE, "타인개인카테고리", 10, null);
        when(categoryPort.findByIdOrThrow(othersPersonalParent.id())).thenReturn(othersPersonalParent);

        FinanceCategoryCommand command = new FinanceCategoryCommand(
                othersPersonalParent.id(), FinanceCategory.Type.EXPENSE, "새카테고리", 10);

        assertThatThrownBy(() -> categoryService.create(userId, null, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("다른 그룹");

        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("생성 시 parentId가 다른 type의 카테고리를 가리키면 IllegalArgumentException")
    void create_parentTypeMismatch_throws() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        FinanceCategory incomeParent = new FinanceCategory(UUID.randomUUID(), groupId, null, userId,
                FinanceCategory.Type.INCOME, "수입부모", 10, null);
        when(categoryPort.findByIdOrThrow(incomeParent.id())).thenReturn(incomeParent);

        FinanceCategoryCommand command = new FinanceCategoryCommand(
                incomeParent.id(), FinanceCategory.Type.EXPENSE, "새카테고리", 10);

        assertThatThrownBy(() -> categoryService.create(userId, null, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("타입");

        verify(categoryPort, never()).save(any());
    }

    // 신규 등록은 항상 개인 소유(groupId=null, userId=userId)로 저장한다 — requestedGroupId는 무시된다.
    @Test
    @DisplayName("생성은 requestedGroupId와 무관하게 항상 개인 소유(groupId=null)로 저장된다")
    void create_alwaysSavesAsPersonalOwnership() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        FinanceCategoryCommand command = new FinanceCategoryCommand(null, FinanceCategory.Type.EXPENSE, "새카테고리", 10);
        when(categoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceCategory result = categoryService.create(userId, groupId, command);

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(userId);
        verify(categoryPort).save(argThat(c -> c.groupId() == null && userId.equals(c.userId())));
    }

    @Test
    @DisplayName("삭제는 softDeleteWithChildren 단일 호출 — find+delete 2단계가 아님")
    void delete_callsSoftDeleteWithChildren() {
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(groupCategory(groupId));
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        categoryService.delete(categoryId, userId);

        verify(categoryPort).softDeleteWithChildren(categoryId);
        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("삭제 시 접근 불가한 카테고리면 SecurityException")
    void delete_notAccessible_throwsSecurityException() {
        FinanceCategory othersGroupCategory = new FinanceCategory(categoryId, UUID.randomUUID(), null,
                UUID.randomUUID(), FinanceCategory.Type.EXPENSE, "식비", 10, null);
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(othersGroupCategory);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.delete(categoryId, userId))
                .isInstanceOf(SecurityException.class);

        verify(categoryPort, never()).softDeleteWithChildren(any());
    }

    @Test
    @DisplayName("list는 findSelectable(userId, currentGroupId, type) 결과를 sortOrder 오름차순으로 정렬해 반환")
    void list_sortsBySortOrder() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        FinanceCategory c30 = new FinanceCategory(UUID.randomUUID(), groupId, null, userId, FinanceCategory.Type.EXPENSE, "c30", 30, null);
        FinanceCategory c10 = new FinanceCategory(UUID.randomUUID(), groupId, null, userId, FinanceCategory.Type.EXPENSE, "c10", 10, null);
        FinanceCategory c20 = new FinanceCategory(UUID.randomUUID(), groupId, null, userId, FinanceCategory.Type.EXPENSE, "c20", 20, null);
        when(categoryPort.findSelectable(userId, groupId, FinanceCategory.Type.EXPENSE))
                .thenReturn(List.of(c30, c10, c20));

        List<FinanceCategory> result = categoryService.list(userId, null, FinanceCategory.Type.EXPENSE);

        assertThat(result).extracting(FinanceCategory::sortOrder).containsExactly(10, 20, 30);
        verify(categoryPort).findSelectable(userId, groupId, FinanceCategory.Type.EXPENSE);
    }

    // 회귀(플랜 항목 1): 타 사용자의 개인 카테고리가 findSelectable에 유출되면 안 된다 — 서비스는 port에
    // 정확히 (userId, currentGroupId)만 넘긴다는 계약을 검증한다(실제 유출 차단은 어댑터/DB 레벨 테스트가 담당).
    @Test
    @DisplayName("list는 무그룹 유저에 대해 currentGroupId=null로 조회해 개인 데이터만 노출한다")
    void list_noCurrentGroup_queriesWithNullGroupId() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(categoryPort.findSelectable(userId, null, null)).thenReturn(List.of());

        categoryService.list(userId, null, null);

        verify(categoryPort).findSelectable(userId, null, null);
    }

    // ── 시스템 카테고리 admin 관리 ──────────────────────────────────────────────

    @Test
    @DisplayName("listSystem은 findSelectable(null, null, type)을 호출하고 sortOrder로 정렬")
    void listSystem_queriesWithNullUserAndGroupId_andSorts() {
        FinanceCategory c20 = new FinanceCategory(UUID.randomUUID(), null, null, null, FinanceCategory.Type.INCOME, "c20", 20, null);
        FinanceCategory c10 = new FinanceCategory(UUID.randomUUID(), null, null, null, FinanceCategory.Type.INCOME, "c10", 10, null);
        when(categoryPort.findSelectable(null, null, FinanceCategory.Type.INCOME)).thenReturn(List.of(c20, c10));

        List<FinanceCategory> result = categoryService.listSystem(FinanceCategory.Type.INCOME);

        assertThat(result).extracting(FinanceCategory::sortOrder).containsExactly(10, 20);
        verifyNoInteractions(financeGroupPort);
    }

    @Test
    @DisplayName("createSystem은 groupId=null, userId=null로 저장하고 그룹 조회를 하지 않음")
    void createSystem_savesWithNullGroupAndUserId() {
        FinanceCategoryCommand command = new FinanceCategoryCommand(null, FinanceCategory.Type.EXPENSE, "새시스템카테고리", 10);
        FinanceCategory saved = new FinanceCategory(UUID.randomUUID(), null, null, null,
                FinanceCategory.Type.EXPENSE, "새시스템카테고리", 10, null);
        when(categoryPort.save(any())).thenReturn(saved);

        FinanceCategory result = categoryService.createSystem(command);

        assertThat(result).isEqualTo(saved);
        verify(categoryPort).save(argThat(c -> c.groupId() == null && c.userId() == null));
        verifyNoInteractions(financeGroupPort);
    }

    @Test
    @DisplayName("createSystem 시 parentId가 시스템이 아닌(그룹 소유) 카테고리를 가리키면 IllegalArgumentException")
    void createSystem_parentNotSystem_throws() {
        FinanceCategory groupParent = groupCategory(groupId);
        when(categoryPort.findByIdOrThrow(groupParent.id())).thenReturn(groupParent);
        FinanceCategoryCommand command = new FinanceCategoryCommand(
                groupParent.id(), FinanceCategory.Type.EXPENSE, "새카테고리", 10);

        assertThatThrownBy(() -> categoryService.createSystem(command))
                .isInstanceOf(IllegalArgumentException.class);

        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("updateSystem은 대상이 시스템 카테고리면 이름·정렬만 갱신")
    void updateSystem_updatesNameAndSortOrder() {
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(systemCategory());
        when(categoryPort.save(any())).thenAnswer(inv -> inv.getArgument(0));

        FinanceCategory result = categoryService.updateSystem(categoryId,
                new FinanceCategoryCommand(null, FinanceCategory.Type.INCOME, "변경명", 20));

        assertThat(result.name()).isEqualTo("변경명");
        assertThat(result.sortOrder()).isEqualTo(20);
        assertThat(result.groupId()).isNull();
    }

    @Test
    @DisplayName("updateSystem 대상이 그룹 카테고리면 IllegalArgumentException")
    void updateSystem_targetIsGroupCategory_throws() {
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(groupCategory(groupId));

        assertThatThrownBy(() -> categoryService.updateSystem(categoryId,
                new FinanceCategoryCommand(null, FinanceCategory.Type.EXPENSE, "변경명", 20)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("deleteSystem은 대상이 시스템 카테고리면 softDeleteWithChildren 호출")
    void deleteSystem_callsSoftDeleteWithChildren() {
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(systemCategory());

        categoryService.deleteSystem(categoryId);

        verify(categoryPort).softDeleteWithChildren(categoryId);
    }

    @Test
    @DisplayName("deleteSystem 대상이 그룹 카테고리면 IllegalArgumentException")
    void deleteSystem_targetIsGroupCategory_throws() {
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(groupCategory(groupId));

        assertThatThrownBy(() -> categoryService.deleteSystem(categoryId))
                .isInstanceOf(IllegalArgumentException.class);

        verify(categoryPort, never()).softDeleteWithChildren(any());
    }

    // 삭제된 카테고리는 findActiveByIdOrThrow가 못 찾아야 함 — findByIdOrThrow(삭제 카테고리도 조회됨)를
    // 쓰면 save() merge 시 deletedAt이 조용히 풀려 되살아난다(코드리뷰에서 발견, 2026-08-19).
    @Test
    @DisplayName("update는 삭제된 카테고리를 되살리지 못하고 404로 거부됨")
    void update_deletedCategory_throwsNotFound() {
        when(categoryPort.findActiveByIdOrThrow(categoryId))
                .thenThrow(new java.util.NoSuchElementException("카테고리를 찾을 수 없습니다: " + categoryId));

        assertThatThrownBy(() -> categoryService.update(categoryId, userId,
                new FinanceCategoryCommand(null, FinanceCategory.Type.INCOME, "변경명", 20)))
                .isInstanceOf(java.util.NoSuchElementException.class);
        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("updateSystem은 삭제된 시스템 카테고리를 되살리지 못하고 404로 거부됨")
    void updateSystem_deletedCategory_throwsNotFound() {
        when(categoryPort.findActiveByIdOrThrow(categoryId))
                .thenThrow(new java.util.NoSuchElementException("카테고리를 찾을 수 없습니다: " + categoryId));

        assertThatThrownBy(() -> categoryService.updateSystem(categoryId,
                new FinanceCategoryCommand(null, FinanceCategory.Type.INCOME, "변경명", 20)))
                .isInstanceOf(java.util.NoSuchElementException.class);
        verify(categoryPort, never()).save(any());
    }

    // ── shareToGroup ────────────────────────────────────────────────────────

    @Test
    @DisplayName("shareToGroup은 본인 소유 개인 카테고리를 현재 그룹으로 cascade 전환")
    void shareToGroup_ownedPersonalCategory_cascadesToChildren() {
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(personalCategory());
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        FinanceCategory reloaded = groupCategory(groupId);
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(reloaded);

        FinanceCategory result = categoryService.shareToGroup(categoryId, userId);

        assertThat(result).isEqualTo(reloaded);
        verify(categoryPort).shareToGroupWithChildren(categoryId, groupId);
        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("shareToGroup은 시스템 카테고리면 그룹 조회 없이 SecurityException")
    void shareToGroup_systemCategory_throwsSecurityException_beforeGroupLookup() {
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(systemCategory());

        assertThatThrownBy(() -> categoryService.shareToGroup(categoryId, userId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("시스템");

        verify(financeGroupPort, never()).findCurrentGroupId(any());
        verify(categoryPort, never()).shareToGroupWithChildren(any(), any());
    }

    @Test
    @DisplayName("shareToGroup은 본인 소유가 아니면 SecurityException")
    void shareToGroup_notOwner_throwsSecurityException() {
        UUID otherOwnerId = UUID.randomUUID();
        FinanceCategory othersPersonal = new FinanceCategory(categoryId, null, null, otherOwnerId,
                FinanceCategory.Type.EXPENSE, "타인개인카테고리", 10, null);
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(othersPersonal);

        assertThatThrownBy(() -> categoryService.shareToGroup(categoryId, userId))
                .isInstanceOf(SecurityException.class);

        verify(categoryPort, never()).shareToGroupWithChildren(any(), any());
    }

    // ── unshare ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("unshare는 그룹 공유 카테고리를 개인 소유로 cascade 되돌림")
    void unshare_groupSharedCategory_cascadesToChildren() {
        FinanceCategory shared = groupCategory(groupId);
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(shared);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        FinanceCategory reloaded = personalCategory();
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(reloaded);

        FinanceCategory result = categoryService.unshare(categoryId, userId);

        assertThat(result).isEqualTo(reloaded);
        verify(categoryPort).unshareWithChildren(categoryId);
        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("unshare는 시스템 카테고리면 그룹 조회 없이 SecurityException")
    void unshare_systemCategory_throwsSecurityException_beforeGroupLookup() {
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(systemCategory());

        assertThatThrownBy(() -> categoryService.unshare(categoryId, userId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("시스템");

        verify(financeGroupPort, never()).findCurrentGroupId(any());
        verify(categoryPort, never()).unshareWithChildren(any());
    }

    @Test
    @DisplayName("unshare는 이미 개인 소유면 cascade 호출 없이 그대로 반환(멱등)")
    void unshare_alreadyPersonal_isIdempotent() {
        FinanceCategory personal = personalCategory();
        when(categoryPort.findActiveByIdOrThrow(categoryId)).thenReturn(personal);
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));

        FinanceCategory result = categoryService.unshare(categoryId, userId);

        assertThat(result).isEqualTo(personal);
        verify(categoryPort, never()).unshareWithChildren(any());
        verify(categoryPort, never()).findByIdOrThrow(any());
    }
}
