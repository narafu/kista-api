package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.model.finance.FinanceGroupMember;
import com.kista.domain.port.in.FinanceGroupUseCase;
import com.kista.domain.port.out.AssetSnapshotPort;
import com.kista.domain.port.out.FinanceAccountPort;
import com.kista.domain.port.out.FinanceBudgetPort;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import com.kista.domain.port.out.FinanceTransactionPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class FinanceGroupService implements FinanceGroupUseCase {

    private final FinanceGroupPort financeGroupPort;
    private final FinanceCategoryPort financeCategoryPort;
    private final FinanceAccountPort financeAccountPort;
    private final FinanceBudgetPort financeBudgetPort;
    private final FinanceTransactionPort financeTransactionPort;
    private final AssetSnapshotPort assetSnapshotPort;

    @Override
    @Transactional(readOnly = true)
    public List<FinanceGroup> listMyGroups(UUID userId) {
        return financeGroupPort.findByMemberUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceGroupMember> listMembers(UUID groupId, UUID userId) {
        financeGroupPort.resolveGroupId(userId, groupId);
        return financeGroupPort.findActiveMembers(groupId);
    }

    @Override
    public FinanceGroupInvitation invite(UUID groupId, UUID userId, long expiresInHours) {
        boolean isOwner = financeGroupPort.findRole(groupId, userId)
                .filter(role -> role == FinanceGroup.MemberRole.OWNER)
                .isPresent();
        if (!isOwner) {
            throw new SecurityException("그룹 소유자만 초대할 수 있습니다");
        }
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant expiresAt = Instant.now().plus(expiresInHours, ChronoUnit.HOURS);
        FinanceGroupInvitation invitation = financeGroupPort.createInvitation(groupId, userId, code, expiresAt);
        log.info("그룹 초대 생성: groupId={}, invitedBy={}", groupId, userId);
        return invitation;
    }

    @Override
    public FinanceGroupInvitation respondToInvitation(String code, UUID userId, FinanceGroupInvitation.Status status) {
        if (status != FinanceGroupInvitation.Status.ACCEPTED && status != FinanceGroupInvitation.Status.DECLINED) {
            throw new IllegalArgumentException("초대 응답은 ACCEPTED 또는 DECLINED만 가능합니다");
        }
        FinanceGroupInvitation invitation = financeGroupPort.findInvitationByCodeOrThrow(code);
        if (invitation.invitedBy().equals(userId)) {
            throw new SecurityException("본인이 발급한 초대 코드는 본인이 수락할 수 없습니다");
        }
        if (invitation.status() != FinanceGroupInvitation.Status.PENDING || invitation.expiresAt().isBefore(Instant.now())) {
            throw new FinanceGroupInvitation.InvalidInvitationStateException("만료되었거나 이미 처리된 초대입니다");
        }
        if (status == FinanceGroupInvitation.Status.ACCEPTED) {
            financeGroupPort.addMember(invitation.groupId(), userId, FinanceGroup.MemberRole.MEMBER);
            // 두 번째 멤버가 들어오는 순간 더 이상 "1인 개인 그룹"이 아니다 — 그대로 두면
            // leaveGroup의 CannotLeavePersonalGroupException이 이 그룹을 영구히 탈퇴 불가로 막는다.
            financeGroupPort.unmarkPersonal(invitation.groupId());
        }
        FinanceGroupInvitation updated = financeGroupPort.updateInvitationStatus(invitation.id(), status, userId);
        log.info("그룹 초대 응답: code={}, userId={}, status={}", code, userId, status);
        return updated;
    }

    // 탈퇴(withdrawal, UserCascadeDeleter)와 다르다 — 이탈은 남은 그룹 데이터를 지우지 않고
    // 이탈자의 created_by 소유 행만 그의 개인 그룹으로 옮긴다.
    @Override
    @Transactional
    public void leaveGroup(UUID groupId, UUID userId, UUID targetUserId) {
        FinanceGroup group = financeGroupPort.findByIdOrThrow(groupId);
        if (group.personal()) {
            throw new FinanceGroup.CannotLeavePersonalGroupException();
        }
        boolean isSelf = userId.equals(targetUserId);
        boolean isOwner = financeGroupPort.findRole(groupId, userId)
                .filter(role -> role == FinanceGroup.MemberRole.OWNER)
                .isPresent();
        if (!isSelf && !isOwner) {
            throw new SecurityException("본인 또는 그룹 소유자만 멤버를 제거할 수 있습니다");
        }
        // 모든 활성 사용자는 가입 시 정확히 1개의 개인 그룹을 갖는다 — 여기서 새로 만들 필요 없음.
        // 이관 대상(개인 그룹)에 이름이 겹치는 카테고리/계좌가 이미 있으면 각 어댑터가 자동으로 병합/개명하지
        // 않고 DuplicateNameException(409, 기존 GlobalExceptionHandler 매핑)으로 실패시킨다 — 어느 쪽을
        // 남길지는 사용자 판단 영역이라 조용히 데이터를 바꾸지 않는다.
        UUID personalGroupId = financeGroupPort.resolveGroupId(targetUserId, null);
        financeCategoryPort.reassignGroup(groupId, personalGroupId, targetUserId);
        financeAccountPort.reassignGroup(groupId, personalGroupId, targetUserId);
        financeBudgetPort.reassignGroup(groupId, personalGroupId, targetUserId);
        financeTransactionPort.reassignGroup(groupId, personalGroupId, targetUserId);
        assetSnapshotPort.reassignGroup(groupId, personalGroupId, targetUserId);
        financeGroupPort.softDeleteMembership(groupId, targetUserId);

        List<FinanceGroupMember> remaining = financeGroupPort.findActiveMembers(groupId).stream()
                .filter(m -> !m.userId().equals(targetUserId))
                .toList();
        if (remaining.isEmpty()) {
            // 마지막 멤버가 나가면 그룹은 아무도 초대할 수 없는 고아 상태가 된다 — 회원 탈퇴 시
            // UserCascadeDeleter가 적용하는 것과 같은 규칙(활성 멤버 0명 → 그룹도 소프트 삭제)을 여기서도 맞춘다.
            financeGroupPort.softDelete(groupId);
        } else if (isOwner && remaining.stream().noneMatch(m -> m.role() == FinanceGroup.MemberRole.OWNER)) {
            // OWNER가 나갔는데 남은 멤버 중 OWNER가 없으면 아무도 다시 초대할 수 없는 그룹이 된다 —
            // 가장 먼저 합류한 남은 멤버를 새 OWNER로 승격한다.
            FinanceGroupMember successor = remaining.stream()
                    .min(java.util.Comparator.comparing(FinanceGroupMember::joinedAt))
                    .orElseThrow();
            financeGroupPort.updateMemberRole(groupId, successor.userId(), FinanceGroup.MemberRole.OWNER);
            log.info("그룹 OWNER 승격: groupId={}, newOwner={}", groupId, successor.userId());
        }
        log.info("그룹 이탈: groupId={}, targetUserId={}, actorUserId={}", groupId, targetUserId, userId);
    }
}
