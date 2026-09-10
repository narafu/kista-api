package com.kista.finance.application.service;

import com.kista.finance.application.port.output.FinanceGroupPort;
import com.kista.finance.domain.model.FinanceGroup;
import com.kista.finance.domain.model.FinanceGroupMember;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.UUID;

// 그룹 멤버십 제거 공용 로직 — FinanceGroupService.leaveGroup(그룹 이탈)과
// UserCascadeListener.onUserDeleted(회원 탈퇴 cascade)가 재사용한다.
// 규칙: 마지막 멤버가 나가면 그룹도 소프트 삭제, OWNER가 나갔는데 남은 멤버 중 OWNER가 없으면
// 가장 먼저 합류한 멤버를 새 OWNER로 승격.
// UserCascadeListener(adapter.out)가 재사용하므로 public — ArchUnit이 adapter.out → application 의존을 허용한다.
@Slf4j
public final class GroupMembershipSupport {

    private GroupMembershipSupport() {
    }

    public static void removeMemberAndHandleSuccession(FinanceGroupPort financeGroupPort, UUID groupId, UUID targetUserId) {
        boolean wasOwner = financeGroupPort.findRole(groupId, targetUserId)
                .filter(role -> role == FinanceGroup.MemberRole.OWNER)
                .isPresent();
        financeGroupPort.softDeleteMembership(groupId, targetUserId);

        // findActiveMembers가 방금 소프트 삭제한 멤버십을 아직 반영하지 않았을 수 있어 방어적으로 재필터링
        var remaining = financeGroupPort.findActiveMembers(groupId).stream()
                .filter(m -> !m.userId().equals(targetUserId))
                .toList();
        if (remaining.isEmpty()) {
            financeGroupPort.softDelete(groupId);
        } else if (wasOwner && remaining.stream().noneMatch(m -> m.role() == FinanceGroup.MemberRole.OWNER)) {
            FinanceGroupMember successor = remaining.stream()
                    .min(Comparator.comparing(FinanceGroupMember::joinedAt))
                    .orElseThrow();
            financeGroupPort.updateMemberRole(groupId, successor.userId(), FinanceGroup.MemberRole.OWNER);
            log.info("그룹 OWNER 승격: groupId={}, newOwner={}", groupId, successor.userId());
        }
    }
}
