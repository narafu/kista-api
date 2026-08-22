package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.model.finance.FinanceGroupMember;
import com.kista.domain.port.out.FinanceGroupPort;
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

// 그룹은 초대로만 생성된다(1인1그룹) — personal 자동생성 그룹·reassignGroup 이관 개념은 V17에서 폐기됐다.
// 리소스(finance_transactions 등)의 group_id는 탈퇴해도 그대로 유지되고 이관하지 않는다.
@ExtendWith(MockitoExtension.class)
@DisplayName("FinanceGroupService 단위 테스트")
class FinanceGroupServiceTest {

    @Mock FinanceGroupPort financeGroupPort;
    @InjectMocks FinanceGroupService financeGroupService;

    private final UUID groupId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID(); // 호출 주체(actor)
    private final UUID targetUserId = UUID.randomUUID(); // 대상(target)

    // ----- invite -----

    @Test
    @DisplayName("무그룹 유저가 초대하면 새 그룹을 만들고 본인을 OWNER로 등록한 뒤 초대를 발급한다")
    void invite_noCurrentGroup_createsGroupAndAddsSelfAsOwner() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.empty());
        when(financeGroupPort.createGroup(userId)).thenReturn(groupId);
        FinanceGroupInvitation invitation = new FinanceGroupInvitation(UUID.randomUUID(), groupId, userId, null,
                "code1234abcd5678", FinanceGroupInvitation.Status.PENDING, Instant.now().plus(72, ChronoUnit.HOURS), null);
        when(financeGroupPort.createInvitation(eq(groupId), eq(userId), anyString(), any(Instant.class)))
                .thenReturn(invitation);

        FinanceGroupInvitation result = financeGroupService.invite(groupId, userId, 72);

        assertThat(result).isEqualTo(invitation);
        verify(financeGroupPort).createGroup(userId);
        verify(financeGroupPort).addMember(groupId, userId, FinanceGroup.MemberRole.OWNER);
    }

    @Test
    @DisplayName("이미 그룹이 있고 OWNER면 초대 성공")
    void invite_hasGroup_owner_succeeds() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        FinanceGroupInvitation invitation = new FinanceGroupInvitation(UUID.randomUUID(), groupId, userId, null,
                "code1234abcd5678", FinanceGroupInvitation.Status.PENDING, Instant.now().plus(72, ChronoUnit.HOURS), null);
        when(financeGroupPort.createInvitation(eq(groupId), eq(userId), anyString(), any(Instant.class)))
                .thenReturn(invitation);

        FinanceGroupInvitation result = financeGroupService.invite(groupId, userId, 72);

        assertThat(result).isEqualTo(invitation);
        verify(financeGroupPort, never()).createGroup(any());
    }

    @Test
    @DisplayName("이미 그룹이 있고 MEMBER(OWNER 아님)면 SecurityException")
    void invite_hasGroup_member_throwsSecurityException() {
        when(financeGroupPort.findCurrentGroupId(userId)).thenReturn(Optional.of(groupId));
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
    @DisplayName("PENDING + 미만료 + ACCEPTED + 무그룹 유저 → addMember 후 updateInvitationStatus 순서로 호출")
    void respond_pendingNotExpiredAccepted_noCurrentGroup_callsAddMemberThenUpdateStatus() {
        FinanceGroupInvitation invitation = pendingInvitation(Instant.now().plus(1, ChronoUnit.HOURS));
        when(financeGroupPort.findInvitationByCodeOrThrow(invitation.code())).thenReturn(invitation);
        when(financeGroupPort.findCurrentGroupId(targetUserId)).thenReturn(Optional.empty());
        when(financeGroupPort.updateInvitationStatus(invitation.id(), FinanceGroupInvitation.Status.ACCEPTED, targetUserId))
                .thenReturn(invitation);

        financeGroupService.respondToInvitation(invitation.code(), targetUserId, FinanceGroupInvitation.Status.ACCEPTED);

        InOrder inOrder = inOrder(financeGroupPort);
        inOrder.verify(financeGroupPort).addMember(invitation.groupId(), targetUserId, FinanceGroup.MemberRole.MEMBER);
        inOrder.verify(financeGroupPort).updateInvitationStatus(invitation.id(), FinanceGroupInvitation.Status.ACCEPTED, targetUserId);
    }

    // 회귀(플랜 항목 7): 1인1그룹 강제 — 이미 다른 그룹에 소속된 사용자가 초대를 수락하려 하면 거부돼야 한다.
    @Test
    @DisplayName("이미 다른 그룹에 소속된 유저가 ACCEPTED 응답하면 IllegalStateException, addMember 호출 안 됨")
    void respond_accepted_alreadyBelongsToAnotherGroup_rejected() {
        FinanceGroupInvitation invitation = pendingInvitation(Instant.now().plus(1, ChronoUnit.HOURS));
        when(financeGroupPort.findInvitationByCodeOrThrow(invitation.code())).thenReturn(invitation);
        when(financeGroupPort.findCurrentGroupId(targetUserId)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> financeGroupService.respondToInvitation(
                invitation.code(), targetUserId, FinanceGroupInvitation.Status.ACCEPTED))
                .isInstanceOf(IllegalStateException.class);

        verify(financeGroupPort, never()).addMember(any(), any(), any());
        verify(financeGroupPort, never()).updateInvitationStatus(any(), any(), any());
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
        verify(financeGroupPort, never()).findCurrentGroupId(any());
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

    @Test
    @DisplayName("본인 탈퇴(self-leave)는 OWNER 여부와 무관하게 성공하고, 남은 OWNER가 있으면 그룹은 유지된다")
    void leaveGroup_selfLeave_remainingOwner_succeeds() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.MEMBER));
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of(
                new FinanceGroupMember(UUID.randomUUID(), groupId, userId, FinanceGroup.MemberRole.MEMBER, Instant.now(), null),
                new FinanceGroupMember(UUID.randomUUID(), groupId, targetUserId, FinanceGroup.MemberRole.OWNER, Instant.now(), null)));

        financeGroupService.leaveGroup(groupId, userId, userId);

        verify(financeGroupPort).softDeleteMembership(groupId, userId);
        verify(financeGroupPort, never()).softDelete(any());
        verify(financeGroupPort, never()).updateMemberRole(any(), any(), any());
    }

    @Test
    @DisplayName("타인 대상 + 호출자 OWNER면 성공 (타인 제거)")
    void leaveGroup_ownerRemovesOther_succeeds() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of(
                new FinanceGroupMember(UUID.randomUUID(), groupId, userId, FinanceGroup.MemberRole.OWNER, Instant.now(), null)));

        financeGroupService.leaveGroup(groupId, userId, targetUserId);

        verify(financeGroupPort).softDeleteMembership(groupId, targetUserId);
        verify(financeGroupPort, never()).softDelete(any());
        verify(financeGroupPort, never()).updateMemberRole(any(), any(), any());
    }

    @Test
    @DisplayName("타인 대상 + 호출자가 일반 MEMBER면 SecurityException")
    void leaveGroup_plainMemberRemovesOther_throws() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.MEMBER));

        assertThatThrownBy(() -> financeGroupService.leaveGroup(groupId, userId, targetUserId))
                .isInstanceOf(SecurityException.class);

        verify(financeGroupPort, never()).softDeleteMembership(any(), any());
    }

    @Test
    @DisplayName("마지막 멤버가 나가면(남은 활성 멤버 0명) 그룹도 소프트 삭제된다")
    void leaveGroup_lastMemberLeaves_softDeletesGroup() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of()); // 이탈자 본인 외 아무도 없음

        financeGroupService.leaveGroup(groupId, userId, userId);

        verify(financeGroupPort).softDelete(groupId);
        verify(financeGroupPort, never()).updateMemberRole(any(), any(), any());
    }

    @Test
    @DisplayName("OWNER가 나가고 남은 멤버 중 OWNER가 없으면 가장 먼저 합류한 멤버가 새 OWNER로 승격된다")
    void leaveGroup_ownerLeavesWithNoOwnerRemaining_promotesEarliestMember() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.OWNER));
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

    // 회귀(플랜 항목 6): 그룹 탈퇴 후 리소스의 group_id는 그대로 유지되고 이관하지 않는다 — leaveGroup()이
    // 카테고리/거래/예산/자산/계좌 포트를 전혀 건드리지 않는지(즉 데이터 이관 로직이 없는지) 검증한다.
    @Test
    @DisplayName("탈퇴는 멤버십만 소프트 삭제할 뿐 다른 어떤 리소스 포트도 건드리지 않는다")
    void leaveGroup_doesNotTouchAnyResourceData() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.of(FinanceGroup.MemberRole.MEMBER));
        when(financeGroupPort.findActiveMembers(groupId)).thenReturn(List.of(
                new FinanceGroupMember(UUID.randomUUID(), groupId, targetUserId, FinanceGroup.MemberRole.OWNER, Instant.now(), null)));

        financeGroupService.leaveGroup(groupId, userId, userId);

        verify(financeGroupPort).softDeleteMembership(groupId, userId);
        verifyNoMoreInteractions(financeGroupPort);
    }

    // ----- listMyGroups / listMembers -----

    @Test
    @DisplayName("listMyGroups는 findByMemberUserId 결과를 그대로 반환")
    void listMyGroups_delegatesToPort() {
        FinanceGroup group = new FinanceGroup(groupId, userId, null);
        when(financeGroupPort.findByMemberUserId(userId)).thenReturn(List.of(group));

        assertThat(financeGroupService.listMyGroups(userId)).containsExactly(group);
    }

    @Test
    @DisplayName("listMembers는 호출자가 멤버가 아니면 SecurityException")
    void listMembers_notAMember_throwsSecurityException() {
        when(financeGroupPort.findRole(groupId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> financeGroupService.listMembers(groupId, userId))
                .isInstanceOf(SecurityException.class);
    }
}
