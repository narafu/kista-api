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
import org.springframework.test.context.transaction.TestTransaction;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({ FinanceGroupPersistenceAdapter.class, FinanceGroupSelfHealer.class })
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
    void resolveGroupId_nullRequestedGroupId_noPersonalGroup_selfHealsAndCreatesOne() {
        // 초대 수락으로 개인 그룹이 personal=false로 전환된 뒤 아무도 복원하지 않는 실제 버그
        // 시나리오(그룹 탈퇴 시 "개인 그룹을 찾을 수 없습니다" 500) 재현 — 개인 그룹이 아예 없는 상태에서
        // resolveGroupId(userId, null) 호출 시 자가 치유로 새로 만들어져야 한다.
        // selfHealer.createPersonalGroup은 REQUIRES_NEW라 별도 커넥션에서 실행돼, @BeforeEach가 심어둔
        // (아직 커밋 전인) users 행을 보지 못하면 FK 위반이 난다 — KisTokenPersistenceAdapterTest와 동일하게
        // 먼저 커밋하고 새 트랜잭션을 시작한다.
        TestTransaction.flagForCommit();
        TestTransaction.end();
        TestTransaction.start();

        UUID resolved = adapter.resolveGroupId(userId, null);

        Boolean personal = jdbcTemplate.queryForObject(
                "SELECT personal FROM finance_groups WHERE id = ? AND owner_user_id = ?",
                Boolean.class, resolved, userId);
        assertThat(personal).isTrue();

        TestTransaction.flagForRollback();
        TestTransaction.end();
        // REQUIRES_NEW로 생성된 그룹은 실제 커밋됐으므로 롤백으로 지워지지 않는다 — 직접 정리한다.
        jdbcTemplate.update("DELETE FROM finance_group_members WHERE group_id = ?", resolved);
        jdbcTemplate.update("DELETE FROM finance_groups WHERE id = ?", resolved);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void resolveGroupId_nullRequestedGroupId_concurrentCallsWithNoPersonalGroup_convergeOnSameGroup() throws Exception {
        // 개인 그룹이 없는 같은 사용자에 대해 resolveGroupId(userId, null)가 동시에 두 번 들어오면
        // 둘 다 SELECT에서 빈 결과를 보고 둘 다 selfHealer.createPersonalGroup을 REQUIRES_NEW로 시도한다 —
        // 진 쪽은 uq_finance_groups_personal_owner 위반으로 DataIntegrityViolationException을 던졌었고,
        // 그 예외를 catch 없이 그대로 흘리면 그 요청은 500이었다(수정 전 회귀). 두 스레드 모두 성공하고
        // 같은 groupId로 수렴해야 한다.
        TestTransaction.flagForCommit();
        TestTransaction.end();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            List<Future<UUID>> futures = List.of(
                    pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return adapter.resolveGroupId(userId, null); }),
                    pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return adapter.resolveGroupId(userId, null); }));

            UUID first = futures.get(0).get(10, TimeUnit.SECONDS);
            UUID second = futures.get(1).get(10, TimeUnit.SECONDS);

            assertThat(first).isNotNull().isEqualTo(second);

            Integer personalGroupCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM finance_groups WHERE owner_user_id = ? AND personal = true",
                    Integer.class, userId);
            assertThat(personalGroupCount).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            // flagForCommit()+end() 이후로 메인 스레드엔 활성 트랜잭션이 없다(의도적 — 백그라운드
            // 스레드 2개가 메인 스레드 트랜잭션에 중첩되지 않고 독립적으로 경쟁해야 하는 테스트라서다).
            // 커밋된 데이터라 롤백으로 안 지워지므로 직접 정리한다.
            jdbcTemplate.update(
                    "DELETE FROM finance_group_members WHERE group_id IN (SELECT id FROM finance_groups WHERE owner_user_id = ?)",
                    userId);
            jdbcTemplate.update("DELETE FROM finance_groups WHERE owner_user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
        }
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
