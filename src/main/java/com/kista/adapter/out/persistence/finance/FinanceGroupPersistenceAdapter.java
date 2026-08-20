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
    private final FinanceGroupSelfHealer selfHealer;

    // 가입 훅(UserService.register) 전용 — 방금 저장한(아직 커밋 전) User 행과 같은 트랜잭션 안에서
    // 실행돼야 한다. selfHealer.createPersonalGroup()은 REQUIRES_NEW라 이 메서드가 그걸 대신 호출하면
    // 커밋 전인 User 행을 새 트랜잭션(별도 커넥션)이 보지 못해 FK 위반으로 가입 자체가 깨진다 — 실측 확인됨.
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
            // 가입 시 생성되는 personal=true 그룹은 소유자가 그 그룹에 멤버를 초대해 공유 그룹으로
            // 전환하면(respondToInvitation의 unmarkPersonal) 다시 personal=true로 복원되지 않는다.
            // "모든 사용자는 개인 그룹을 정확히 1개 갖는다" 불변식이 깨진 상태로 여기 도달하면
            // 그 자리에서 새로 만들어 자가 치유한다 — 이 메서드가 유일한 personal-group 해석 지점이라
            // 호출부(leaveGroup·거래/예산/계좌/카테고리/자산스냅샷 서비스) 전체에 한 번에 적용된다.
            return groupJpaRepository.findByOwnerUserIdAndPersonalTrue(userId)
                    .map(FinanceGroupEntity::getId)
                    .orElseGet(() -> createPersonalGroupRacingSafe(userId));
        }
        boolean isMember = memberJpaRepository.findByGroupIdAndUserId(requestedGroupId, userId).isPresent();
        if (!isMember) {
            throw new SecurityException("그룹에 대한 접근 권한이 없습니다");
        }
        return requestedGroupId;
    }

    // 개인 그룹이 없는 같은 사용자의 동시 요청 2개가 둘 다 findByOwnerUserIdAndPersonalTrue에서 빈 결과를
    // 보고 둘 다 이 메서드로 들어올 수 있다 — selfHealer.createPersonalGroup은 REQUIRES_NEW(별도 커넥션)라
    // 진 쪽의 INSERT는 uq_finance_groups_personal_owner를 위반해 DataIntegrityViolationException을
    // 던진다. addMember처럼 ON CONFLICT DO NOTHING을 못 쓰는 이유는 두 번째 INSERT(멤버십 행까지 포함)라
    // 단일 upsert로 표현하기 애매해서다 — 대신 이긴 쪽의 커밋을 이 시점엔 볼 수 있으므로 재조회로 흡수한다.
    // REQUIRES_NEW라 이 예외는 진 쪽의 내부 트랜잭션만 롤백시키고 resolveGroupId 자신의 트랜잭션은
    // 멀쩡하다 — addMember 주석의 "abort 전파" 문제와 달리 여기선 catch가 안전하다.
    private UUID createPersonalGroupRacingSafe(UUID userId) {
        try {
            return selfHealer.createPersonalGroup(userId);
        } catch (DataIntegrityViolationException e) {
            return groupJpaRepository.findByOwnerUserIdAndPersonalTrue(userId)
                    .map(FinanceGroupEntity::getId)
                    .orElseThrow(() -> e);
        }
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
