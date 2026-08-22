package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.MonthlyClosing;
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

// (group_id, month)/(user_id, month) 유니크 partial index 위 네이티브 upsert 검증 — race 없는 in-place 갱신이 핵심
@Import(FinanceMonthlyClosingPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceMonthlyClosingPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired FinanceMonthlyClosingPersistenceAdapter adapter;

    private UUID userId;
    private UUID groupId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        groupId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, created_at, updated_at) VALUES (?, ?, now(), now())",
                groupId, userId);
    }

    @Test
    void upsert_group_sameGroupAndMonth_updatesInPlace_notDuplicateKey() {
        adapter.upsert(groupId, userId, "2026-08", true);
        adapter.upsert(groupId, userId, "2026-08", false);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_monthly_closings WHERE group_id = ? AND month = ?",
                Integer.class, groupId, "2026-08");
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void upsert_group_completedTrue_setsClosedAtNotNull_userIdStaysOwner() {
        MonthlyClosing result = adapter.upsert(groupId, userId, "2026-08", true);

        assertThat(result.completed()).isTrue();
        assertThat(result.userId()).isEqualTo(userId); // 소유자 축 — 마감 해제해도 null로 되돌지 않는다
        assertThat(result.closedAt()).isNotNull();
    }

    @Test
    void upsert_group_completedFalse_setsClosedAtNull_butKeepsUserId() {
        // 먼저 마감했다가 해제하는 시나리오 — completed=false 전환 시 closed_at은 NULL로 되돌아가지만
        // user_id(소유자 축)는 유지된다(V17 이후 closed_by와 달리 절대 null로 되돌지 않음).
        adapter.upsert(groupId, userId, "2026-08", true);
        entityManager.clear();

        MonthlyClosing result = adapter.upsert(groupId, userId, "2026-08", false);

        assertThat(result.completed()).isFalse();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.closedAt()).isNull();
    }

    // groupId=null(개인 마감) 경로 — 어댑터가 upsertPersonal로 분기해야 한다
    @Test
    void upsert_personal_nullGroupId_updatesInPlace_notDuplicateKey() {
        adapter.upsert(null, userId, "2026-08", true);
        adapter.upsert(null, userId, "2026-08", false);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_monthly_closings WHERE user_id = ? AND group_id IS NULL AND month = ?",
                Integer.class, userId, "2026-08");
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void upsert_personal_returnsGroupIdNullAndOwnerUserId() {
        MonthlyClosing result = adapter.upsert(null, userId, "2026-08", true);

        assertThat(result.groupId()).isNull();
        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.completed()).isTrue();
    }

    // 회귀(플랜 항목 4): 개인 마감과 그룹 마감은 서로 다른 partial unique index라 같은 사용자·같은 월이라도 공존한다.
    @Test
    void findMyScope_returnsPersonalUnionGroup() {
        adapter.upsert(null, userId, "2026-08", true);
        adapter.upsert(groupId, userId, "2026-08", false);

        var result = adapter.findMyScope(userId, groupId);

        assertThat(result).hasSize(2);
    }

    @Test
    void findMyScope_noGroup_returnsOnlyPersonal() {
        adapter.upsert(null, userId, "2026-08", true);
        adapter.upsert(groupId, userId, "2026-08", false);

        var result = adapter.findMyScope(userId, null);

        assertThat(result).extracting(MonthlyClosing::groupId).containsExactly((UUID) null);
    }

    @Test
    void deleteByGroupId_removesGroupClosings() {
        adapter.upsert(groupId, userId, "2026-08", true);

        adapter.deleteByGroupId(groupId);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_monthly_closings WHERE group_id = ?", Integer.class, groupId);
        assertThat(rowCount).isZero();
    }
}
