package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.model.finance.FinanceCategoryCommand;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    @DisplayName("시스템 카테고리 수정 시 SecurityException — isSystem 체크가 그룹 멤버십 체크보다 먼저 실행됨")
    void update_systemCategory_throwsSecurityException_beforeMembershipCheck() {
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(systemCategory());

        assertThatThrownBy(() -> categoryService.update(categoryId, userId,
                new FinanceCategoryCommand(null, FinanceCategory.Type.INCOME, "변경명", 20)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("시스템");

        // isSystem 체크가 먼저 실행되므로 그룹 멤버십 검증(resolveGroupId)까지 도달하지 않는다.
        verify(financeGroupPort, never()).resolveGroupId(any(), any());
        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("시스템 카테고리 삭제 시 SecurityException — isSystem 체크가 그룹 멤버십 체크보다 먼저 실행됨")
    void delete_systemCategory_throwsSecurityException_beforeMembershipCheck() {
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(systemCategory());

        assertThatThrownBy(() -> categoryService.delete(categoryId, userId))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("시스템");

        verify(financeGroupPort, never()).resolveGroupId(any(), any());
        verify(categoryPort, never()).softDeleteWithChildren(any());
    }

    @Test
    @DisplayName("비회원이 시스템 카테고리를 수정 시도해도 non-member가 아니라 is-system 사유로 거부됨")
    void update_nonMemberOnSystemCategory_rejectedForBeingSystem_notNonMembership() {
        // financeGroupPort는 아예 호출되지 않아야 하므로 stub하지 않는다 — 호출되면 stub 부재로 NPE가 나서
        // "멤버십 체크가 실제로는 실행되지 않았다"는 사실이 즉시 드러난다.
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(systemCategory());

        assertThatThrownBy(() -> categoryService.update(categoryId, userId,
                new FinanceCategoryCommand(null, FinanceCategory.Type.INCOME, "변경명", 20)))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("시스템")
                .hasMessageNotContaining("권한"); // 비멤버 사유("...접근 권한이 없습니다")와는 다른 메시지여야 함

        verifyNoInteractions(financeGroupPort);
    }

    @Test
    @DisplayName("생성 시 parentId가 타 그룹 소유 카테고리를 가리키면 IllegalArgumentException")
    void create_parentBelongsToDifferentGroup_throws() {
        UUID otherGroupId = UUID.randomUUID();
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory otherGroupParent = new FinanceCategory(UUID.randomUUID(), otherGroupId, null, userId,
                FinanceCategory.Type.EXPENSE, "타그룹부모", 10, null);
        when(categoryPort.findByIdOrThrow(otherGroupParent.id())).thenReturn(otherGroupParent);

        FinanceCategoryCommand command = new FinanceCategoryCommand(
                otherGroupParent.id(), FinanceCategory.Type.EXPENSE, "새카테고리", 10);

        assertThatThrownBy(() -> categoryService.create(userId, null, command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("다른 그룹");

        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("생성 시 parentId가 이미 L2(부모의 부모 존재)인 카테고리를 가리켜도 depth 무관 정책상 정상 생성됨")
    void create_parentIsAlreadyL2_allowedUnderDepthAgnosticPolicy() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory l2Parent = new FinanceCategory(UUID.randomUUID(), groupId, UUID.randomUUID(), userId,
                FinanceCategory.Type.EXPENSE, "이미L2", 10, null);
        when(categoryPort.findByIdOrThrow(l2Parent.id())).thenReturn(l2Parent);
        FinanceCategory saved = new FinanceCategory(UUID.randomUUID(), groupId, l2Parent.id(), userId,
                FinanceCategory.Type.EXPENSE, "새카테고리", 10, null);
        when(categoryPort.save(any())).thenReturn(saved);

        FinanceCategoryCommand command = new FinanceCategoryCommand(
                l2Parent.id(), FinanceCategory.Type.EXPENSE, "새카테고리", 10);

        assertThat(categoryService.create(userId, null, command)).isEqualTo(saved);
        verify(categoryPort).save(any());
    }

    @Test
    @DisplayName("생성 시 parentId가 다른 type의 카테고리를 가리키면 IllegalArgumentException")
    void create_parentTypeMismatch_throws() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
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

    @Test
    @DisplayName("삭제는 softDeleteWithChildren 단일 호출 — find+delete 2단계가 아님")
    void delete_callsSoftDeleteWithChildren() {
        when(categoryPort.findByIdOrThrow(categoryId)).thenReturn(groupCategory(groupId));
        when(financeGroupPort.resolveGroupId(userId, groupId)).thenReturn(groupId);

        categoryService.delete(categoryId, userId);

        verify(categoryPort).softDeleteWithChildren(categoryId);
        verify(categoryPort, never()).save(any());
    }

    @Test
    @DisplayName("list는 port 결과를 sortOrder 오름차순으로 정렬해 반환")
    void list_sortsBySortOrder() {
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(groupId);
        FinanceCategory c30 = new FinanceCategory(UUID.randomUUID(), groupId, null, userId, FinanceCategory.Type.EXPENSE, "c30", 30, null);
        FinanceCategory c10 = new FinanceCategory(UUID.randomUUID(), groupId, null, userId, FinanceCategory.Type.EXPENSE, "c10", 10, null);
        FinanceCategory c20 = new FinanceCategory(UUID.randomUUID(), groupId, null, userId, FinanceCategory.Type.EXPENSE, "c20", 20, null);
        when(categoryPort.findSelectableByGroup(groupId, FinanceCategory.Type.EXPENSE))
                .thenReturn(List.of(c30, c10, c20));

        List<FinanceCategory> result = categoryService.list(userId, null, FinanceCategory.Type.EXPENSE);

        assertThat(result).extracting(FinanceCategory::sortOrder).containsExactly(10, 20, 30);
        verify(categoryPort).findSelectableByGroup(groupId, FinanceCategory.Type.EXPENSE);
    }
}
