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

// (group_id, month) 유니크 제약 위 네이티브 upsert 검증 — race 없는 in-place 갱신이 핵심
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
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '개인', true, now(), now())",
                groupId, userId);
    }

    @Test
    void upsert_sameGroupAndMonth_updatesInPlace_notDuplicateKey() {
        adapter.upsert(groupId, userId, "2026-08", true);
        adapter.upsert(groupId, userId, "2026-08", false);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_monthly_closings WHERE group_id = ? AND month = ?",
                Integer.class, groupId, "2026-08");
        assertThat(rowCount).isEqualTo(1);
    }

    @Test
    void upsert_completedTrue_setsClosedByToProvidedUser_andClosedAtNotNull() {
        MonthlyClosing result = adapter.upsert(groupId, userId, "2026-08", true);

        assertThat(result.completed()).isTrue();
        assertThat(result.closedBy()).isEqualTo(userId);
        assertThat(result.closedAt()).isNotNull();
    }

    @Test
    void upsert_completedFalse_setsClosedByAndClosedAtNull() {
        // 먼저 마감했다가 해제하는 시나리오 — completed=false 전환 시 closed_by/closed_at은 NULL로 되돌아간다
        adapter.upsert(groupId, userId, "2026-08", true);
        // upsert()의 조회부는 네이티브 @Modifying 다음 일반 JPA 조회라 1차 캐시를 그대로 반환할 수 있다 —
        // 같은 영속성 컨텍스트에서 같은 PK를 두 번째 upsert() 하기 전에 비워야 두 번째 결과가 최신 상태를 반영한다.
        entityManager.clear();

        MonthlyClosing result = adapter.upsert(groupId, userId, "2026-08", false);

        assertThat(result.completed()).isFalse();
        assertThat(result.closedBy()).isNull();
        assertThat(result.closedAt()).isNull();
    }
}
