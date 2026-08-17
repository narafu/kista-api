package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.domain.model.finance.FinanceGroupInvitation;
import com.kista.domain.model.finance.FinanceGroupMember;
import com.kista.domain.port.out.FinanceGroupPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
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
    public UUID createPersonalGroup(UUID userId) {
        FinanceGroupEntity group = new FinanceGroupEntity();
        group.setOwnerUserId(userId);
        group.setName("개인");
        group.setPersonal(true);
        FinanceGroupEntity saved = groupJpaRepository.save(group);

        FinanceGroupMemberEntity member = new FinanceGroupMemberEntity();
        member.setGroupId(saved.getId());
        member.setUserId(userId);
        member.setRole(FinanceGroup.MemberRole.OWNER);
        member.setJoinedAt(Instant.now());
        memberJpaRepository.save(member);

        return saved.getId();
    }

    @Override
    public UUID resolveGroupId(UUID userId, UUID requestedGroupId) {
        if (requestedGroupId == null) {
            return groupJpaRepository.findByOwnerUserIdAndPersonalTrue(userId)
                    .map(FinanceGroupEntity::getId)
                    .orElseThrow(() -> new NoSuchElementException("개인 그룹을 찾을 수 없습니다: " + userId));
        }
        boolean isMember = memberJpaRepository.findByGroupIdAndUserId(requestedGroupId, userId).isPresent();
        if (!isMember) {
            throw new SecurityException("그룹에 대한 접근 권한이 없습니다");
        }
        return requestedGroupId;
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
        // ON CONFLICT DO NOTHING으로 멱등하게 처리한다 — 같은 초대 코드의 동시 이중 수락, 또는 같은 그룹에 대한
        // 두 개의 PENDING 초대가 각각 수락되는 경쟁 상황을 흡수한다. 존재 확인 후 catch(DataIntegrityViolationException)
        // 방식은 쓰지 않는다: PostgreSQL은 제약 위반이 나는 순간 그 트랜잭션 전체를 abort 상태로 넘겨버려서,
        // Java 레벨에서 예외를 삼켜도 respondToInvitation의 다음 문장(updateInvitationStatus)이
        // "current transaction is aborted"로 실패한다.
        memberJpaRepository.insertIfAbsent(groupId, userId, role.name());
    }

    @Override
    public void unmarkPersonal(UUID groupId) {
        groupJpaRepository.unmarkPersonalById(groupId);
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
