package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.model.finance.FinanceGroupMember;
import com.kista.domain.port.out.FinanceGroupPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

// 그룹 + 멤버 + 초대 3테이블을 한 애그리게이트로 덮는 어댑터 — 3개 JpaRepository를 내부에서 조율한다.
@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class FinanceGroupPersistenceAdapter implements FinanceGroupPort {

    private final FinanceGroupJpaRepository groupJpaRepository;
    private final FinanceGroupMemberJpaRepository memberJpaRepository;
    private final FinanceGroupInvitationJpaRepository invitationJpaRepository;

    @Override
    public Optional<UUID> findCurrentGroupId(UUID userId) {
        return groupJpaRepository.findCurrentGroupId(userId);
    }

    @Override
    public UUID createGroup(UUID ownerUserId, String name) {
        FinanceGroupEntity entity = FinanceGroupEntity.fromModel(new FinanceGroup(null, ownerUserId, name, null));
        return groupJpaRepository.save(entity).getId();
    }

    @Override
    public List<FinanceGroup> findByMemberUserId(UUID userId) {
        return groupJpaRepository.findByMemberUserId(userId).stream()
                .map(FinanceGroupEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceGroup> findById(UUID id) {
        return groupJpaRepository.findById(id).map(FinanceGroupEntity::toDomain);
    }

    @Override
    public List<FinanceGroupMember> findActiveMembers(UUID groupId) {
        return memberJpaRepository.findByGroupId(groupId).stream()
                .map(FinanceGroupMemberEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<FinanceGroup.MemberRole> findRole(UUID groupId, UUID userId) {
        return memberJpaRepository.findByGroupIdAndUserId(groupId, userId).map(FinanceGroupMemberEntity::getRole);
    }

    @Override
    public void addMember(UUID groupId, UUID userId, FinanceGroup.MemberRole role) {
        // (group_id, user_id) 중복은 ON CONFLICT DO NOTHING으로 멱등하게 흡수한다 — 같은 초대 코드의 동시
        // 이중 수락, 또는 같은 그룹에 대한 두 개의 PENDING 초대가 각각 수락되는 경쟁 상황이 대상이다.
        // 반면 uq_finance_group_members_one_active_group(user_id 단독, 1인1그룹 불변식)은 insertIfAbsent의
        // ON CONFLICT 대상이 아니라서 위반 시 그대로 예외가 올라온다 — 서로 다른 두 그룹 초대를 동시에
        // 수락하는 TOCTOU 레이스(FinanceGroupService.respondToInvitation의 findCurrentGroupId 사전검증만으론
        // 못 막는 창구)의 최종 방어선. 트랜잭션이 이 지점에서 즉시 abort되므로 다음 문장을 실행하지 않고
        // 곧바로 도메인 예외로 변환해 던진다 — 어차피 join 실패는 전체 트랜잭션 롤백이 맞는 동작이다.
        try {
            memberJpaRepository.insertIfAbsent(groupId, userId, role.name());
        } catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("이미 다른 그룹에 소속되어 있습니다 — 먼저 탈퇴해야 합니다", e);
        }
    }

    @Override
    public void updateMemberRole(UUID groupId, UUID userId, FinanceGroup.MemberRole role) {
        memberJpaRepository.updateRole(groupId, userId, role);
    }

    @Override
    public void softDeleteMembership(UUID groupId, UUID userId) {
        memberJpaRepository.softDeleteByGroupIdAndUserId(groupId, userId, Instant.now());
    }

    @Override
    public void softDelete(UUID groupId) {
        groupJpaRepository.softDeleteById(groupId, Instant.now());
    }

    @Override
    public FinanceGroupInvitation createInvitation(UUID groupId, UUID invitedBy, String code, Instant expiresAt) {
        FinanceGroupInvitationEntity invitation = new FinanceGroupInvitationEntity();
        invitation.setGroupId(groupId);
        invitation.setInvitedBy(invitedBy);
        invitation.setCode(code);
        invitation.setStatus(FinanceGroupInvitation.Status.PENDING);
        invitation.setExpiresAt(expiresAt);
        return FinanceGroupInvitationEntity.toDomain(invitationJpaRepository.save(invitation));
    }

    @Override
    public Optional<FinanceGroupInvitation> findInvitationByCode(String code) {
        return invitationJpaRepository.findByCode(code).map(FinanceGroupInvitationEntity::toDomain);
    }

    @Override
    public FinanceGroupInvitation updateInvitationStatus(UUID invitationId, FinanceGroupInvitation.Status status, UUID inviteeUserId) {
        FinanceGroupInvitationEntity invitation = invitationJpaRepository.findById(invitationId)
                .orElseThrow(() -> new NoSuchElementException("초대를 찾을 수 없습니다: " + invitationId));
        invitation.setStatus(status);
        if (inviteeUserId != null) {
            invitation.setInviteeUserId(inviteeUserId);
        }
        return FinanceGroupInvitationEntity.toDomain(invitationJpaRepository.save(invitation));
    }
}
