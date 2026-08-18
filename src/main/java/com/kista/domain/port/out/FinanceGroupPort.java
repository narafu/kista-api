package com.kista.domain.port.out;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.model.finance.FinanceGroupMember;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

// 그룹 + 멤버 + 초대 3테이블을 한 애그리게이트로 덮는다 (테이블당 포트 1개는 과잉).
public interface FinanceGroupPort {

    UUID createPersonalGroup(UUID userId); // 가입 훅. 재실행·동시 가입 경쟁은 uq_finance_groups_personal_owner가 DB에서 차단

    // requestedGroupId가 null이면 개인 그룹 id 반환, 아니면 멤버십 검증 후 그대로 반환.
    // 비멤버로 지정하면 SecurityException → 컨트롤러에서 403 매핑. 모든 재무 서비스의 단일 소유권 진입점.
    UUID resolveGroupId(UUID userId, UUID requestedGroupId);

    List<FinanceGroup> findByMemberUserId(UUID userId); // 내가 속한 그룹 전체

    Optional<FinanceGroup> findById(UUID id);

    default FinanceGroup findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("그룹을 찾을 수 없습니다: " + id));
    }

    List<FinanceGroupMember> findActiveMembers(UUID groupId);

    Optional<FinanceGroup.MemberRole> findRole(UUID groupId, UUID userId);

    // 이미 활성 멤버면 아무 일도 하지 않는다(멱등) — 같은 초대 코드가 동시에 두 번 수락되거나
    // 같은 그룹에 대해 두 개의 PENDING 초대가 각각 수락되는 경쟁을 안전하게 흡수한다.
    void addMember(UUID groupId, UUID userId, FinanceGroup.MemberRole role);

    // 두 번째 멤버가 들어오는 순간 더 이상 "1인 개인 그룹"이 아니게 된다 — personal=false로 전환해
    // FinanceGroup.CannotLeavePersonalGroupException이 더는 이 그룹을 막지 않게 한다. 이미 false여도 안전.
    void unmarkPersonal(UUID groupId);

    void updateMemberRole(UUID groupId, UUID userId, FinanceGroup.MemberRole role);

    void softDeleteMembership(UUID groupId, UUID userId);

    void softDelete(UUID groupId);

    FinanceGroupInvitation createInvitation(UUID groupId, UUID invitedBy, String code, Instant expiresAt);

    Optional<FinanceGroupInvitation> findInvitationByCode(String code);

    default FinanceGroupInvitation findInvitationByCodeOrThrow(String code) {
        return findInvitationByCode(code).orElseThrow(
                () -> new NoSuchElementException("초대를 찾을 수 없습니다: " + code));
    }

    FinanceGroupInvitation updateInvitationStatus(UUID invitationId, FinanceGroupInvitation.Status status, UUID inviteeUserId);
}
