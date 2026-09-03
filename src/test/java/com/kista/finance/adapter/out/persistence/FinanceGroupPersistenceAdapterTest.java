package com.kista.finance.adapter.out.persistence;

import com.kista.finance.domain.model.FinanceGroup;
import com.kista.finance.domain.model.FinanceGroupInvitation;
import com.kista.support.DataJpaTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 그룹은 초대로만 생성된다(1인1그룹) — personal 자동생성 그룹 개념은 V17에서 폐기됐다.
@Import(FinanceGroupPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceGroupPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired FinanceGroupPersistenceAdapter adapter;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
    }

    @Test
    void findCurrentGroupId_noMembership_returnsEmpty() {
        assertThat(adapter.findCurrentGroupId(userId)).isEmpty();
    }

    @Test
    void createGroup_thenAddMember_findCurrentGroupIdReturnsIt() {
        UUID groupId = adapter.createGroup(userId);
        adapter.addMember(groupId, userId, FinanceGroup.MemberRole.OWNER);

        assertThat(adapter.findCurrentGroupId(userId)).contains(groupId);
        assertThat(adapter.findByMemberUserId(userId)).extracting(FinanceGroup::id).containsExactly(groupId);
    }

    @Test
    void addMember_calledTwiceForSameGroupAndUser_isIdempotentAndDoesNotPoisonTransaction() {
        UUID groupId = adapter.createGroup(userId);

        // ON CONFLICT DO NOTHING이라 두 번째 호출도 예외 없이 끝나야 한다 — 존재 확인 후
        // catch(DataIntegrityViolationException)로 삼키던 옛 구현은 두 번째 INSERT가 제약을 위반하는 순간
        // 이 트랜잭션 전체를 abort 상태로 만들어, 바로 다음 줄의 DB 호출이 실패했다.
        adapter.addMember(groupId, userId, FinanceGroup.MemberRole.MEMBER);
        adapter.addMember(groupId, userId, FinanceGroup.MemberRole.MEMBER);

        assertThat(adapter.findActiveMembers(groupId)).hasSize(1);
    }

    @Test
    void findRole_activeMember_returnsRole() {
        UUID groupId = adapter.createGroup(userId);
        adapter.addMember(groupId, userId, FinanceGroup.MemberRole.OWNER);

        assertThat(adapter.findRole(groupId, userId)).contains(FinanceGroup.MemberRole.OWNER);
    }

    @Test
    void findRole_softDeletedMembership_returnsEmpty() {
        UUID groupId = adapter.createGroup(userId);
        adapter.addMember(groupId, userId, FinanceGroup.MemberRole.MEMBER);
        adapter.softDeleteMembership(groupId, userId);

        assertThat(adapter.findRole(groupId, userId)).isEmpty();
    }

    @Test
    void updateMemberRole_changesRole() {
        UUID groupId = adapter.createGroup(userId);
        adapter.addMember(groupId, userId, FinanceGroup.MemberRole.MEMBER);

        adapter.updateMemberRole(groupId, userId, FinanceGroup.MemberRole.OWNER);

        assertThat(adapter.findRole(groupId, userId)).contains(FinanceGroup.MemberRole.OWNER);
    }

    @Test
    void softDelete_excludesGroupFromFindById() {
        UUID groupId = adapter.createGroup(userId);

        adapter.softDelete(groupId);
        entityManager.clear();

        assertThat(adapter.findById(groupId)).isEmpty();
    }

    @Test
    void createInvitation_thenFindByCode_roundTrips() {
        UUID groupId = adapter.createGroup(userId);
        String code = "abcd1234efgh5678";
        Instant expiresAt = Instant.now().plus(72, ChronoUnit.HOURS);

        FinanceGroupInvitation created = adapter.createInvitation(groupId, userId, code, expiresAt);

        assertThat(created.status()).isEqualTo(FinanceGroupInvitation.Status.PENDING);
        assertThat(adapter.findInvitationByCode(code)).contains(created);
    }

    @Test
    void updateInvitationStatus_setsStatusAndInviteeUserId() {
        UUID groupId = adapter.createGroup(userId);
        UUID inviteeId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                inviteeId, "kakao_" + inviteeId, "ACTIVE", "USER");
        FinanceGroupInvitation invitation = adapter.createInvitation(groupId, userId, "code9999zzzz0000",
                Instant.now().plus(72, ChronoUnit.HOURS));

        FinanceGroupInvitation updated = adapter.updateInvitationStatus(
                invitation.id(), FinanceGroupInvitation.Status.ACCEPTED, inviteeId);

        assertThat(updated.status()).isEqualTo(FinanceGroupInvitation.Status.ACCEPTED);
        assertThat(updated.inviteeUserId()).isEqualTo(inviteeId);
    }
}
