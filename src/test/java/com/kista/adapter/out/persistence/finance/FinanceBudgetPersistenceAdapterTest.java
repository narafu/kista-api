package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceBudget;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// finance_budgets_no_overlap EXCLUDE 제약(§4.1) 실효성 검증 — 이 테스트가 이 파일의 핵심 목적이다.
// 주의: DataIntegrityViolationException을 유발하는 saveAndFlush 호출은 Postgres 트랜잭션을
// aborted 상태로 만든다 — 예외를 기대하는 각 테스트는 그 save() 호출을 메서드의 마지막 DB 접촉으로 둔다.
@Import(FinanceBudgetPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceBudgetPersistenceAdapterTest extends DataJpaTestBase {

    // V13 시드값 — 시스템 카테고리 (EXPENSE L1) 두 개, 예산 FK 대상으로만 사용
    private static final UUID CATEGORY_A = UUID.fromString("f1000000-0000-4000-8000-000000000201"); // 주거비
    private static final UUID CATEGORY_B = UUID.fromString("f1000000-0000-4000-8000-000000000202"); // 생활비

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired FinanceBudgetPersistenceAdapter adapter;

    private UUID userId;
    private UUID groupId;
    private UUID otherGroupId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        groupId = UUID.randomUUID();
        otherGroupId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '개인', true, now(), now())",
                groupId, userId);
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '가족', false, now(), now())",
                otherGroupId, userId);
    }

    private FinanceBudget budget(UUID gId, UUID categoryId, LocalDate start, LocalDate end) {
        return new FinanceBudget(null, gId, categoryId, userId, start, end, 500_000L, null);
    }

    @Test
    void save_nonOverlappingRangesSameGroupAndCategory_bothSaveFine() {
        FinanceBudget first = adapter.save(budget(groupId, CATEGORY_A, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));
        FinanceBudget second = adapter.save(budget(groupId, CATEGORY_A, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isNotNull();
    }

    @Test
    void save_overlappingRanges_inclusiveBoundaryEdgeCase_throwsOverlappingPeriodException() {
        // daterange(..., '[]') — 종료일 포함이라 한쪽 종료일 == 다른쪽 시작일도 겹침으로 간주된다
        adapter.save(budget(groupId, CATEGORY_A, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)));

        assertThatThrownBy(() -> adapter.save(
                budget(groupId, CATEGORY_A, LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 15))))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);
    }

    @Test
    void save_openEndedBudget_thenAnyFutureStart_conflicts() {
        adapter.save(budget(groupId, CATEGORY_A, LocalDate.of(2026, 3, 1), null));

        assertThatThrownBy(() -> adapter.save(
                budget(groupId, CATEGORY_A, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30))))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);
    }

    @Test
    void save_twoOpenEndedBudgets_sameGroupAndCategory_conflicts() {
        adapter.save(budget(groupId, CATEGORY_A, LocalDate.of(2026, 5, 1), null));

        assertThatThrownBy(() -> adapter.save(
                budget(groupId, CATEGORY_A, LocalDate.of(2026, 9, 1), null)))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);
    }

    @Test
    void save_closedBudget_thenOpenEndedStartingBeforeItsEndDate_conflicts() {
        adapter.save(budget(groupId, CATEGORY_A, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)));

        assertThatThrownBy(() -> adapter.save(
                budget(groupId, CATEGORY_A, LocalDate.of(2026, 6, 15), null)))
                .isInstanceOf(FinanceBudget.OverlappingPeriodException.class);
    }

    @Test
    void save_closedBudget_thenOpenEndedStartingStrictlyAfterItsEndDate_noConflict() {
        FinanceBudget first = adapter.save(budget(groupId, CATEGORY_A, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)));
        FinanceBudget second = adapter.save(budget(groupId, CATEGORY_A, LocalDate.of(2026, 8, 1), null));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isNotNull();
    }

    @Test
    void save_sameOverlappingDates_differentCategory_noConflict() {
        LocalDate start = LocalDate.of(2026, 10, 1);
        LocalDate end = LocalDate.of(2026, 10, 31);
        FinanceBudget first = adapter.save(budget(groupId, CATEGORY_A, start, end));
        FinanceBudget second = adapter.save(budget(groupId, CATEGORY_B, start, end));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isNotNull();
    }

    @Test
    void save_sameOverlappingDates_differentGroup_noConflict() {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 30);
        FinanceBudget first = adapter.save(budget(groupId, CATEGORY_A, start, end));
        FinanceBudget second = adapter.save(budget(otherGroupId, CATEGORY_A, start, end));

        assertThat(first.id()).isNotNull();
        assertThat(second.id()).isNotNull();
    }
}
