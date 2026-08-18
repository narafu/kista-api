package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceTransaction;
import com.kista.support.DataJpaTestBase;
import jakarta.persistence.EntityManager;
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

@Import(FinanceTransactionPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceTransactionPersistenceAdapterTest extends DataJpaTestBase {

    // V13 시드값 — 시스템 카테고리 (EXPENSE L1) 두 개
    private static final UUID CATEGORY_A = UUID.fromString("f1000000-0000-4000-8000-000000000201"); // 주거비
    private static final UUID CATEGORY_B = UUID.fromString("f1000000-0000-4000-8000-000000000202"); // 생활비

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired FinanceTransactionPersistenceAdapter adapter;

    private UUID userId;
    private UUID otherUserId;
    private UUID groupId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        groupId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                otherUserId, "kakao_" + otherUserId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '개인', true, now(), now())",
                groupId, userId);
    }

    private FinanceTransaction transaction(UUID categoryId, UUID createdBy, LocalDate date, long amount) {
        return new FinanceTransaction(null, groupId, categoryId, createdBy, date, amount, "메모", null);
    }

    @Test
    void save_andFindById_roundTrips() {
        FinanceTransaction saved = adapter.save(transaction(CATEGORY_A, userId, LocalDate.of(2026, 8, 1), 15_000L));

        FinanceTransaction found = adapter.findById(saved.id()).orElseThrow();

        assertThat(found.groupId()).isEqualTo(groupId);
        assertThat(found.categoryId()).isEqualTo(CATEGORY_A);
        assertThat(found.createdBy()).isEqualTo(userId);
        assertThat(found.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(found.amount()).isEqualTo(15_000L);
        assertThat(found.memo()).isEqualTo("메모");
    }

    @Test
    void softDelete_setsDeletedAt_andExcludesFromFindById() {
        FinanceTransaction saved = adapter.save(transaction(CATEGORY_A, userId, LocalDate.now(), 1_000L));

        adapter.softDelete(saved.id());
        // softDelete는 @Modifying 벌크 UPDATE라 1차 캐시를 자동 갱신하지 않는다 — clear()로 비워야 한다
        entityManager.clear();

        // @SQLRestriction("deleted_at IS NULL")로 인해 findById도 소프트 삭제된 행은 못 찾는다
        assertThat(adapter.findById(saved.id())).isEmpty();
        var deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM finance_transactions WHERE id = ?", java.sql.Timestamp.class, saved.id());
        assertThat(deletedAt).isNotNull();
    }

    @Test
    void findByGroupId_categoryAndCreatedByFilters_matchExactCombination() {
        // NOTE: from/to(LocalDate) 필터는 이 테스트에서 의도적으로 검증하지 않는다 — 실제 값을 넘기면
        // FinanceTransactionJpaRepository.findByGroupId의 "(:from IS NULL OR ...)" 패턴이 Postgres에서
        // "could not determine data type of parameter" SQLGrammarException을 던진다 (findByGroupId
        // 파라미터가 null일 때만 우연히 통과). 재현 확인 후 어댑터 코드는 건드리지 않고 리포트로 남긴다.
        LocalDate targetDate = LocalDate.of(2026, 6, 15);
        FinanceTransaction target = adapter.save(transaction(CATEGORY_A, userId, targetDate, 10_000L));

        adapter.save(transaction(CATEGORY_B, userId, targetDate, 40_000L));      // 다른 카테고리
        adapter.save(transaction(CATEGORY_A, otherUserId, targetDate, 50_000L)); // 다른 입력자

        var result = adapter.findByGroupId(groupId, null, null, CATEGORY_A, userId);

        assertThat(result).extracting(FinanceTransaction::id).containsExactly(target.id());
    }

    @Test
    void findByGroupId_nullFilters_returnAllActiveInGroup() {
        adapter.save(transaction(CATEGORY_A, userId, LocalDate.of(2026, 1, 1), 1_000L));
        adapter.save(transaction(CATEGORY_B, otherUserId, LocalDate.of(2026, 2, 1), 2_000L));

        var result = adapter.findByGroupId(groupId, null, null, null, null);

        assertThat(result).hasSize(2);
    }
}
