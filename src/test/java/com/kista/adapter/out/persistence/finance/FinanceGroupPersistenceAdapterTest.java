package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceGroup;
import com.kista.support.DataJpaTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void createPersonalGroup_createsPersonalGroupAndOwnerMembership() {
        UUID groupId = adapter.createPersonalGroup(userId);
        // adapter는 순수 JPA save()(saveAndFlush 아님)를 쓰므로 raw JdbcTemplate으로 바로 조회하면
        // 아직 flush 전인 INSERT가 안 보일 수 있다 — 같은 커넥션의 별도 SELECT라 자동 플러시 대상이 아니다.
        entityManager.flush();

        Integer groupCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_groups WHERE id = ? AND owner_user_id = ? AND personal = true",
                Integer.class, groupId, userId);
        Integer memberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_group_members WHERE group_id = ? AND user_id = ? AND role = 'OWNER'",
                Integer.class, groupId, userId);

        assertThat(groupCount).isEqualTo(1);
        assertThat(memberCount).isEqualTo(1);
    }

    @Test
    void resolveGroupId_nullRequestedGroupId_returnsPersonalGroupId() {
        UUID personalGroupId = adapter.createPersonalGroup(userId);

        UUID resolved = adapter.resolveGroupId(userId, null);

        assertThat(resolved).isEqualTo(personalGroupId);
    }

    @Test
    void resolveGroupId_requestedGroupId_activeMember_returnsUnchanged() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '가족', false, now(), now())",
                otherGroupId, userId);
        adapter.addMember(otherGroupId, userId, FinanceGroup.MemberRole.MEMBER);

        UUID resolved = adapter.resolveGroupId(userId, otherGroupId);

        assertThat(resolved).isEqualTo(otherGroupId);
    }

    @Test
    void resolveGroupId_requestedGroupId_notAMember_throwsSecurityException() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '가족', false, now(), now())",
                otherGroupId, userId);
        // userId를 멤버로 추가하지 않는다

        assertThatThrownBy(() -> adapter.resolveGroupId(userId, otherGroupId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void resolveGroupId_requestedGroupId_softDeletedMembership_throwsSecurityException() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '가족', false, now(), now())",
                otherGroupId, userId);
        adapter.addMember(otherGroupId, userId, FinanceGroup.MemberRole.MEMBER);
        adapter.softDeleteMembership(otherGroupId, userId);

        // 멤버십 확인은 deleted_at을 반영해야 한다 — @SQLRestriction("deleted_at IS NULL")이 걸린
        // FinanceGroupMemberEntity를 통해 조회하므로 소프트 삭제된 멤버십은 "멤버 아님"으로 취급돼야 한다
        assertThatThrownBy(() -> adapter.resolveGroupId(userId, otherGroupId))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void addMember_calledTwiceForSameGroupAndUser_isIdempotentAndDoesNotPoisonTransaction() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '가족', false, now(), now())",
                otherGroupId, userId);

        // ON CONFLICT DO NOTHING이라 두 번째 호출도 예외 없이 끝나야 한다. 존재 확인 후
        // catch(DataIntegrityViolationException)로 삼키던 옛 구현은 두 번째 INSERT가 제약을 위반하는 순간
        // 이 트랜잭션 전체를 abort 상태로 만들어, 바로 다음 줄의 resolveGroupId 호출이
        // "current transaction is aborted"로 실패했다 — 그 회귀를 그대로 재현해 검증한다.
        adapter.addMember(otherGroupId, userId, FinanceGroup.MemberRole.MEMBER);
        adapter.addMember(otherGroupId, userId, FinanceGroup.MemberRole.MEMBER);

        UUID resolved = adapter.resolveGroupId(userId, otherGroupId); // 같은 트랜잭션의 다음 DB 호출
        assertThat(resolved).isEqualTo(otherGroupId);

        Integer memberRowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_group_members WHERE group_id = ? AND user_id = ?",
                Integer.class, otherGroupId, userId);
        assertThat(memberRowCount).isEqualTo(1);
    }

    @Test
    void unmarkPersonal_flipsPersonalFlagToFalse() {
        UUID personalGroupId = adapter.createPersonalGroup(userId);

        adapter.unmarkPersonal(personalGroupId);
        entityManager.flush();
        entityManager.clear();

        Boolean personal = jdbcTemplate.queryForObject(
                "SELECT personal FROM finance_groups WHERE id = ?", Boolean.class, personalGroupId);
        assertThat(personal).isFalse();
    }

    @Test
    void updateMemberRole_changesRole() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '가족', false, now(), now())",
                otherGroupId, userId);
        adapter.addMember(otherGroupId, userId, FinanceGroup.MemberRole.MEMBER);

        adapter.updateMemberRole(otherGroupId, userId, FinanceGroup.MemberRole.OWNER);
        entityManager.flush();
        entityManager.clear();

        String role = jdbcTemplate.queryForObject(
                "SELECT role FROM finance_group_members WHERE group_id = ? AND user_id = ?",
                String.class, otherGroupId, userId);
        assertThat(role).isEqualTo("OWNER");
    }
}
