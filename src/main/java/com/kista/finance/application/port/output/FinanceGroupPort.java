package com.kista.finance.application.port.output;

import com.kista.finance.domain.model.FinanceGroup;
import com.kista.finance.domain.model.FinanceGroupInvitation;
import com.kista.finance.domain.model.FinanceGroupMember;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

// 그룹 + 멤버 + 초대 3테이블을 한 애그리게이트로 덮는다 (테이블당 포트 1개는 과잉).
public interface FinanceGroupPort {

    // 1인1그룹 불변식이므로 결과는 최대 1개. 무그룹 유저는 empty.
    Optional<UUID> findCurrentGroupId(UUID userId);

    // 무그룹 유저가 초대를 발급하는 시점에 새 그룹을 만든다. 생성자는 OWNER로 별도 addMember 호출 필요.
    UUID createGroup(UUID ownerUserId);

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
