package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.model.finance.FinanceGroupMember;
import com.kista.domain.port.out.AssetSnapshotPort;
import com.kista.domain.port.out.FinanceAccountPort;
import com.kista.domain.port.out.FinanceBudgetPort;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import com.kista.domain.port.out.FinanceTransactionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FinanceGroupService 단위 테스트")
class FinanceGroupServiceTest {

    @Mock FinanceGroupPort financeGroupPort;
    @Mock FinanceCategoryPort financeCategoryPort;
    @Mock FinanceAccountPort financeAccountPort;
    @Mock FinanceBudgetPort financeBudgetPort;
    @Mock FinanceTransactionPort financeTransactionPort;
    @Mock AssetSnapshotPort assetSnapshotPort;
    @InjectMocks FinanceGroupService financeGroupService;

    private final UUID groupId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID(); // 호출 주체(actor)
    private final UUID targetUserId = UUID.randomUUID(); // 대상(target) — self-leave 테스트에서는 userId와 동일하게 사용

    // ----- invite -----

    @Test
    @DisplayName("OWNER가 초대하면 성공, createInvitation 호출")
    void invite_owner_succeeds() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        FinanceGroupInvitation invitation = new FinanceGroupInvitation(UUID.randomUUID(), groupId, userId, null,
                "code1234abcd5678", FinanceGroupInvitation.Status.PENDING, Instant.now().plus(72, ChronoUnit.HOURS), null);
        when(financeGroupPort.createInvitation(eq(groupId), eq(userId), anyString(), any(Instant.class)))
                .thenReturn(invitation);

        FinanceGroupInvitation result = financeGroupService.invite(groupId, userId, 72);

        assertThat(result).isEqualTo(invitation);
        verify(financeGroupPort).createInvitation(eq(groupId), eq(userId), anyString(), any(Instant.class));
    }

    @Test
    @DisplayName("MEMBER(OWNER 아님)가 초대하면 SecurityException")
    void invite_member_throwsSecurityException() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.MEMBER));

        assertThatThrownBy(() -> financeGroupService.invite(groupId, userId, 72))
                .isInstanceOf(SecurityException.class);

        verify(financeGroupPort, never()).createInvitation(any(), any(), any(), any());
    }

    // ----- respondToInvitation -----

    private FinanceGroupInvitation pendingInvitation(Instant expiresAt) {
        return new FinanceGroupInvitation(UUID.randomUUID(), groupId, userId, null,
                "codeabcd12345678", FinanceGroupInvitation.Status.PENDING, expiresAt, null);
    }

    @Test
    @DisplayName("PENDING + 미만료 + ACCEPTED → addMember 후 updateInvitationStatus 순서로 호출")
    void respond_pendingNotExpiredAccepted_callsAddMemberThenUpdateStatus() {
        FinanceGroupInvitation invitation = pendingInvitation(Instant.now().plus(1, ChronoUnit.HOURS));
        when(financeGroupPort.findInvitationByCodeOrThrow(invitation.code())).thenReturn(invitation);
        when(financeGroupPort.updateInvitationStatus(invitation.id(), FinanceGroupInvitation.Status.ACCEPTED, targetUserId))
                .thenReturn(invitation);

        financeGroupService.respondToInvitation(invitation.code(), targetUserId, FinanceGroupInvitation.Status.ACCEPTED);

        InOrder inOrder = inOrder(financeGroupPort);
        inOrder.verify(financeGroupPort).addMember(invitation.groupId(), targetUserId, FinanceGroup.MemberRole.MEMBER);
        inOrder.verify(financeGroupPort).updateInvitationStatus(invitation.id(), FinanceGroupInvitation.Status.ACCEPTED, targetUserId);
        // 두 번째 멤버가 들어오면 더 이상 "1인 개인 그룹"이 아니므로 personal 플래그를 해제해야 leaveGroup이
        // 이 그룹을 영구히 탈퇴 불가로 막지 않는다.
        verify(financeGroupPort).unmarkPersonal(invitation.groupId());
    }

    @Test
    @DisplayName("PENDING + 미만료 + DECLINED → updateInvitationStatus만 호출, addMember는 호출 안 됨")
    void respond_pendingNotExpiredDeclined_callsOnlyUpdateStatus() {
        FinanceGroupInvitation invitation = pendingInvitation(Instant.now().plus(1, ChronoUnit.HOURS));
        when(financeGroupPort.findInvitationByCodeOrThrow(invitation.code())).thenReturn(invitation);
        when(financeGroupPort.updateInvitationStatus(invitation.id(), FinanceGroupInvitation.Status.DECLINED, targetUserId))
                .thenReturn(invitation);

        financeGroupService.respondToInvitation(invitation.code(), targetUserId, FinanceGroupInvitation.Status.DECLINED);

        verify(financeGroupPort, never()).addMember(any(), any(), any());
        verify(financeGroupPort).updateInvitationStatus(invitation.id(), FinanceGroupInvitation.Status.DECLINED, targetUserId);
        verify(financeGroupPort, never()).unmarkPersonal(any());
    }

    @Test
    @DisplayName("이미 ACCEPTED인 초대에 응답하면 InvalidInvitationStateException")
    void respond_alreadyAccepted_throwsInvalidState() {
        FinanceGroupInvitation invitation = new FinanceGroupInvitation(UUID.randomUUID(), groupId, userId, targetUserId,
                "codeaccepted1234", FinanceGroupInvitation.Status.ACCEPTED, Instant.now().plus(1, ChronoUnit.HOURS), null);
        when(financeGroupPort.findInvitationByCodeOrThrow(invitation.code())).thenReturn(invitation);

        assertThatThrownBy(() -> financeGroupService.respondToInvitation(invitation.code(), targetUserId, FinanceGroupInvitation.Status.ACCEPTED))
                .isInstanceOf(FinanceGroupInvitation.InvalidInvitationStateException.class);

        verify(financeGroupPort, never()).addMember(any(), any(), any());
        verify(financeGroupPort, never()).updateInvitationStatus(any(), any(), any());
    }

    @Test
    @DisplayName("이미 DECLINED인 초대에 응답하면 InvalidInvitationStateException")
    void respond_alreadyDeclined_throwsInvalidState() {
        FinanceGroupInvitation invitation = new FinanceGroupInvitation(UUID.randomUUID(), groupId, userId, targetUserId,
                "codedeclined1234", FinanceGroupInvitation.Status.DECLINED, Instant.now().plus(1, ChronoUnit.HOURS), null);
        when(financeGroupPort.findInvitationByCodeOrThrow(invitation.code())).thenReturn(invitation);

        assertThatThrownBy(() -> financeGroupService.respondToInvitation(invitation.code(), targetUserId, FinanceGroupInvitation.Status.ACCEPTED))
                .isInstanceOf(FinanceGroupInvitation.InvalidInvitationStateException.class);

        verify(financeGroupPort, never()).addMember(any(), any(), any());
    }

    @Test
    @DisplayName("발급자 본인이 자기 초대 코드를 수락하면 SecurityException")
    void respond_selfAccept_throwsSecurityException() {
        FinanceGroupInvitation invitation = pendingInvitation(Instant.now().plus(1, ChronoUnit.HOURS));
        when(financeGroupPort.findInvitationByCodeOrThrow(invitation.code())).thenReturn(invitation);

        assertThatThrownBy(() -> financeGroupService.respondToInvitation(invitation.code(), userId, FinanceGroupInvitation.Status.ACCEPTED))
                .isInstanceOf(SecurityException.class);

        verify(financeGroupPort, never()).addMember(any(), any(), any());
        verify(financeGroupPort, never()).updateInvitationStatus(any(), any(), any());
    }

    @Test
    @DisplayName("만료된 초대는 status가 여전히 PENDING이어도 InvalidInvitationStateException")
    void respond_expiredButStillPending_throwsInvalidState() {
        FinanceGroupInvitation invitation = pendingInvitation(Instant.now().minus(1, ChronoUnit.HOURS));
        when(financeGroupPort.findInvitationByCodeOrThrow(invitation.code())).thenReturn(invitation);

        assertThatThrownBy(() -> financeGroupService.respondToInvitation(invitation.code(), targetUserId, FinanceGroupInvitation.Status.ACCEPTED))
                .isInstanceOf(FinanceGroupInvitation.InvalidInvitationStateException.class);

        verify(financeGroupPort, never()).addMember(any(), any(), any());
        verify(financeGroupPort, never()).updateInvitationStatus(any(), any(), any());
    }

    // ----- leaveGroup -----

    private FinanceGroup personalGroup() {
        return new FinanceGroup(groupId, userId, "개인", true, null);
    }

    private FinanceGroup sharedGroup() {
        return new FinanceGroup(groupId, userId, "부부 가계부", false, null);
    }

    @Test
    @DisplayName("대상 그룹이 personal=true면 CannotLeavePersonalGroupException")
    void leaveGroup_personalGroup_throws() {
        when(financeGroupPort.findByIdOrThrow(groupId)).thenReturn(personalGroup());

        assertThatThrownBy(() -> financeGroupService.leaveGroup(groupId, userId, userId))
                .isInstanceOf(FinanceGroup.CannotLeavePersonalGroupException.class);

        verifyNoInteractions(financeCategoryPort, financeAccountPort, financeBudgetPort, financeTransactionPort, assetSnapshotPort);
    }

    @Test
    @DisplayName("비개인 그룹, 본인 탈퇴(self-leave)는 성공 — OWNER 여부와 무관")
    void leaveGroup_nonPersonal_selfLeave_succeeds() {
        when(financeGroupPort.findByIdOrThrow(groupId)).thenReturn(sharedGroup());
        // isOwner는 isSelf와 무관하게 항상 계산되므로 findRole은 self-leave 케이스에서도 stub 필요.
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.MEMBER));
        UUID personalGroupId = UUID.randomUUID();
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(personalGroupId);
        // 그룹에 여전히 OWNER인 다른 멤버가 남아 있는 상태 — 그룹 소프트 삭제도, OWNER 승격도 일어나지 않아야 한다.
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of(
                new FinanceGroupMember(UUID.randomUUID(), groupId, userId, FinanceGroup.MemberRole.MEMBER, Instant.now(), null),
                new FinanceGroupMember(UUID.randomUUID(), groupId, targetUserId, FinanceGroup.MemberRole.OWNER, Instant.now(), null)));

        financeGroupService.leaveGroup(groupId, userId, userId);

        verify(financeCategoryPort).reassignGroup(groupId, personalGroupId, userId);
        verify(financeAccountPort).reassignGroup(groupId, personalGroupId, userId);
        verify(financeBudgetPort).reassignGroup(groupId, personalGroupId, userId);
        verify(financeTransactionPort).reassignGroup(groupId, personalGroupId, userId);
        verify(assetSnapshotPort).reassignGroup(groupId, personalGroupId, userId);
        verify(financeGroupPort).softDeleteMembership(groupId, userId);
        verify(financeGroupPort, never()).softDelete(any());
        verify(financeGroupPort, never()).updateMemberRole(any(), any(), any());
    }

    @Test
    @DisplayName("비개인 그룹, 타인 대상 + 호출자 OWNER면 성공 (타인 제거)")
    void leaveGroup_nonPersonal_ownerRemovesOther_succeeds() {
        when(financeGroupPort.findByIdOrThrow(groupId)).thenReturn(sharedGroup());
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        UUID personalGroupId = UUID.randomUUID();
        when(financeGroupPort.resolveGroupId(targetUserId, null)).thenReturn(personalGroupId);
        // 제거되는 대상은 targetUserId — 호출자(userId, OWNER)는 그대로 남아 있으므로 승격도 그룹 삭제도 없어야 한다.
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of(
                new FinanceGroupMember(UUID.randomUUID(), groupId, userId, FinanceGroup.MemberRole.OWNER, Instant.now(), null)));

        financeGroupService.leaveGroup(groupId, userId, targetUserId);

        verify(financeCategoryPort).reassignGroup(groupId, personalGroupId, targetUserId);
        verify(financeAccountPort).reassignGroup(groupId, personalGroupId, targetUserId);
        verify(financeBudgetPort).reassignGroup(groupId, personalGroupId, targetUserId);
        verify(financeTransactionPort).reassignGroup(groupId, personalGroupId, targetUserId);
        verify(assetSnapshotPort).reassignGroup(groupId, personalGroupId, targetUserId);
        verify(financeGroupPort).softDeleteMembership(groupId, targetUserId);
        verify(financeGroupPort, never()).softDelete(any());
        verify(financeGroupPort, never()).updateMemberRole(any(), any(), any());
    }

    @Test
    @DisplayName("마지막 멤버가 나가면(남은 활성 멤버 0명) 그룹도 소프트 삭제된다")
    void leaveGroup_lastMemberLeaves_softDeletesGroup() {
        when(financeGroupPort.findByIdOrThrow(groupId)).thenReturn(sharedGroup());
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        UUID personalGroupId = UUID.randomUUID();
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(personalGroupId);
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of()); // 이탈자 본인 외 아무도 없음

        financeGroupService.leaveGroup(groupId, userId, userId);

        verify(financeGroupPort).softDelete(groupId);
        verify(financeGroupPort, never()).updateMemberRole(any(), any(), any());
    }

    @Test
    @DisplayName("OWNER가 나가고 남은 멤버 중 OWNER가 없으면 가장 먼저 합류한 멤버가 새 OWNER로 승격된다")
    void leaveGroup_ownerLeavesWithNoOwnerRemaining_promotesEarliestMember() {
        when(financeGroupPort.findByIdOrThrow(groupId)).thenReturn(sharedGroup());
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        UUID personalGroupId = UUID.randomUUID();
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(personalGroupId);
        UUID laterMemberId = UUID.randomUUID();
        Instant earlier = Instant.now().minus(10, ChronoUnit.HOURS);
        Instant later = Instant.now();
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of(
                new FinanceGroupMember(UUID.randomUUID(), groupId, laterMemberId, FinanceGroup.MemberRole.MEMBER, later, null),
                new FinanceGroupMember(UUID.randomUUID(), groupId, targetUserId, FinanceGroup.MemberRole.MEMBER, earlier, null)));

        financeGroupService.leaveGroup(groupId, userId, userId);

        verify(financeGroupPort).updateMemberRole(groupId, targetUserId, FinanceGroup.MemberRole.OWNER);
        verify(financeGroupPort, never()).softDelete(any());
    }

    @Test
    @DisplayName("비개인 그룹, 타인 대상 + 호출자가 일반 MEMBER면 SecurityException")
    void leaveGroup_nonPersonal_plainMemberRemovesOther_throws() {
        when(financeGroupPort.findByIdOrThrow(groupId)).thenReturn(sharedGroup());
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.MEMBER));

        assertThatThrownBy(() -> financeGroupService.leaveGroup(groupId, userId, targetUserId))
                .isInstanceOf(SecurityException.class);

        verifyNoInteractions(financeCategoryPort, financeAccountPort, financeBudgetPort, financeTransactionPort, assetSnapshotPort);
        verify(financeGroupPort, never()).softDeleteMembership(any(), any());
    }

    @Test
    @DisplayName("이관 대상 그룹에 이름이 겹치는 카테고리가 있으면 DuplicateNameException이 그대로 전파됨")
    void leaveGroup_categoryNameCollisionInTargetGroup_propagatesUntouched() {
        when(financeGroupPort.findByIdOrThrow(groupId)).thenReturn(sharedGroup());
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.MEMBER));
        UUID personalGroupId = UUID.randomUUID();
        when(financeGroupPort.resolveGroupId(userId, null)).thenReturn(personalGroupId);
        doThrow(new com.kista.domain.model.finance.FinanceCategory.DuplicateNameException("식비"))
                .when(financeCategoryPort).reassignGroup(groupId, personalGroupId, userId);

        // 어댑터가 DuplicateNameException(기존 409 매핑)으로 변환한다 — 서비스는 감싸지 않고 그대로 전파만 한다.
        assertThatThrownBy(() -> financeGroupService.leaveGroup(groupId, userId, userId))
                .isInstanceOf(com.kista.domain.model.finance.FinanceCategory.DuplicateNameException.class);

        verify(financeGroupPort, never()).softDeleteMembership(any(), any());
    }
}
