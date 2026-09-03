package com.kista.finance.adapter.out;

import com.kista.user.application.event.UserDeletedEvent;
import com.kista.finance.application.port.output.AssetSnapshotPort;
import com.kista.finance.application.port.output.FinanceAccountPort;
import com.kista.finance.application.port.output.FinanceBudgetPort;
import com.kista.finance.application.port.output.FinanceCategoryPort;
import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.application.port.output.FinanceTransactionPort;
import com.kista.finance.domain.model.FinanceGroup;
import com.kista.finance.domain.model.FinanceGroupMember;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("finance UserCascadeListener 단위 테스트")
class UserCascadeListenerTest {

    @Mock FinanceTransactionPort financeTransactionPort;
    @Mock AssetSnapshotPort assetSnapshotPort;
    @Mock FinanceAccountPort financeAccountPort;
    @Mock FinanceCategoryPort financeCategoryPort;
    @Mock FinanceBudgetPort financeBudgetPort;
    @Mock FinanceGroupPort financeGroupPort;

    @InjectMocks UserCascadeListener listener;

    private final UUID userId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    private FinanceGroup sharedGroup() {
        return new FinanceGroup(groupId, userId, null);
    }

    private void fire() {
        listener.onUserDeleted(new UserDeletedEvent(userId));
    }

    @Test
    @DisplayName("userId 단위로 재무 기록을 정리하고, 그룹 통째 삭제 대신 멤버십만 정리한다")
    void onUserDeleted_scopesFinanceCleanupByUserId() {
        when(financeGroupPort.findByMemberUserId(userId)).thenReturn(List.of());

        fire();

        verify(financeTransactionPort).softDeleteByUserId(userId);
        verify(assetSnapshotPort).softDeleteByUserId(userId);
        verify(financeAccountPort).softDeleteByUserId(userId);
        verify(financeCategoryPort).softDeleteByUserId(userId);
        verify(financeBudgetPort).deleteByUserId(userId);
    }

    @Test
    @DisplayName("마지막 멤버가 탈퇴하면(남은 활성 멤버 0명) 그룹도 소프트 삭제된다")
    void onUserDeleted_lastMemberOfGroup_softDeletesGroup() {
        when(financeGroupPort.findByMemberUserId(userId)).thenReturn(List.of(sharedGroup()));
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of());

        fire();

        verify(financeGroupPort).softDeleteMembership(groupId, userId);
        verify(financeGroupPort).softDelete(groupId);
        verify(financeGroupPort, never()).updateMemberRole(any(), any(), any());
    }

    @Test
    @DisplayName("탈퇴 사용자가 유일한 OWNER였고 다른 멤버가 남아 있으면 가장 먼저 합류한 멤버가 새 OWNER로 승격된다")
    void onUserDeleted_soleOwnerWithRemainingMembers_promotesEarliestMember() {
        UUID remainingUserId = UUID.randomUUID();
        when(financeGroupPort.findByMemberUserId(userId)).thenReturn(List.of(sharedGroup()));
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of(
                new FinanceGroupMember(UUID.randomUUID(), groupId, remainingUserId,
                        FinanceGroup.MemberRole.MEMBER, Instant.now(), null)));

        fire();

        verify(financeGroupPort).updateMemberRole(groupId, remainingUserId, FinanceGroup.MemberRole.OWNER);
        verify(financeGroupPort, never()).softDelete(any());
    }

    @Test
    @DisplayName("탈퇴 사용자가 일반 MEMBER였고 다른 멤버가 남아 있으면 승격도 그룹 삭제도 없다")
    void onUserDeleted_plainMemberWithRemainingMembers_noPromotionNoDeletion() {
        UUID remainingOwnerId = UUID.randomUUID();
        when(financeGroupPort.findByMemberUserId(userId)).thenReturn(List.of(sharedGroup()));
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.MEMBER));
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of(
                new FinanceGroupMember(UUID.randomUUID(), groupId, remainingOwnerId,
                        FinanceGroup.MemberRole.OWNER, Instant.now(), null)));

        fire();

        verify(financeGroupPort, never()).updateMemberRole(any(), any(), any());
        verify(financeGroupPort, never()).softDelete(any());
    }
}
