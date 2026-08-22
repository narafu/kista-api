package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.model.finance.FinanceGroupMember;
import com.kista.domain.port.in.FinanceGroupUseCase;
import com.kista.domain.port.out.FinanceGroupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class FinanceGroupService implements FinanceGroupUseCase {

    private final FinanceGroupPort financeGroupPort;

    @Override
    @Transactional(readOnly = true)
    public List<FinanceGroup> listMyGroups(UUID userId) {
        return financeGroupPort.findByMemberUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceGroupMember> listMembers(UUID groupId, UUID userId) {
        if (financeGroupPort.findRole(groupId, userId).isEmpty()) {
            throw new SecurityException("그룹 멤버만 멤버 목록을 조회할 수 있습니다");
        }
        return financeGroupPort.findActiveMembers(groupId);
    }

    // 무그룹 유저가 초대하면 그 자리에서 새 그룹을 만들고 본인을 OWNER로 등록한다. 이미 그룹이 있으면
    // 그 그룹의 OWNER만 초대할 수 있다(기존 정책 유지).
    @Override
    public FinanceGroupInvitation invite(UUID groupId, UUID userId, long expiresInHours) {
        UUID targetGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        if (targetGroupId == null) {
            targetGroupId = financeGroupPort.createGroup(userId);
            financeGroupPort.addMember(targetGroupId, userId, FinanceGroup.MemberRole.OWNER);
        } else {
            boolean isOwner = financeGroupPort.findRole(targetGroupId, userId)
                    .filter(role -> role == FinanceGroup.MemberRole.OWNER)
                    .isPresent();
            if (!isOwner) {
                throw new SecurityException("그룹 소유자만 초대할 수 있습니다");
            }
        }
        String code = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Instant expiresAt = Instant.now().plus(expiresInHours, ChronoUnit.HOURS);
        FinanceGroupInvitation invitation = financeGroupPort.createInvitation(targetGroupId, userId, code, expiresAt);
        log.info("그룹 초대 생성: groupId={}, invitedBy={}", targetGroupId, userId);
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
            // 1인1그룹 강제 — 이미 다른 그룹에 소속돼 있으면 수락 자체를 거부한다.
            if (financeGroupPort.findCurrentGroupId(userId).isPresent()) {
                throw new IllegalStateException("이미 다른 그룹에 소속되어 있습니다 — 먼저 탈퇴해야 합니다");
            }
            financeGroupPort.addMember(invitation.groupId(), userId, FinanceGroup.MemberRole.MEMBER);
        }
        FinanceGroupInvitation updated = financeGroupPort.updateInvitationStatus(invitation.id(), status, userId);
        log.info("그룹 초대 응답: code={}, userId={}, status={}", code, userId, status);
        return updated;
    }

    // 그룹 이탈 — 리소스(finance_transactions 등)의 group_id는 그대로 유지되고 이관하지 않는다.
    // 나간 사람은 더 이상 멤버가 아니므로 findMyScope에서 그룹 데이터가 자연히 제외되고, 남은 사람은
    // 계속 조회된다. 회원 탈퇴(UserCascadeDeleter)와 달리 그룹 자체는 남길 수 있다.
    @Override
    @Transactional
    public void leaveGroup(UUID groupId, UUID userId, UUID targetUserId) {
        boolean isSelf = userId.equals(targetUserId);
        boolean isOwner = financeGroupPort.findRole(groupId, userId)
                .filter(role -> role == FinanceGroup.MemberRole.OWNER)
                .isPresent();
        if (!isSelf && !isOwner) {
            throw new SecurityException("본인 또는 그룹 소유자만 멤버를 제거할 수 있습니다");
        }
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
                    .min(Comparator.comparing(FinanceGroupMember::joinedAt))
                    .orElseThrow();
            financeGroupPort.updateMemberRole(groupId, successor.userId(), FinanceGroup.MemberRole.OWNER);
            log.info("그룹 OWNER 승격: groupId={}, newOwner={}", groupId, successor.userId());
        }
        log.info("그룹 이탈: groupId={}, targetUserId={}, actorUserId={}", groupId, targetUserId, userId);
    }
}
