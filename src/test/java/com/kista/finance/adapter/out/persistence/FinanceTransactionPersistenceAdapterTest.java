package com.kista.finance.adapter.out.persistence;

import com.kista.finance.domain.model.FinanceTransaction;
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
                "INSERT INTO finance_groups (id, owner_user_id, created_at, updated_at) VALUES (?, ?, now(), now())",
                groupId, userId);
    }

    private FinanceTransaction groupTransaction(UUID categoryId, UUID owner, LocalDate date, long amount) {
        return new FinanceTransaction(null, groupId, categoryId, owner, date, amount, "메모", null);
    }

    private FinanceTransaction personalTransaction(UUID categoryId, UUID owner, LocalDate date, long amount) {
        return new FinanceTransaction(null, null, categoryId, owner, date, amount, "메모", null);
    }

    @Test
    void save_andFindById_roundTrips() {
        FinanceTransaction saved = adapter.save(groupTransaction(CATEGORY_A, userId, LocalDate.of(2026, 8, 1), 15_000L));

        FinanceTransaction found = adapter.findById(saved.id()).orElseThrow();

        assertThat(found.groupId()).isEqualTo(groupId);
        assertThat(found.categoryId()).isEqualTo(CATEGORY_A);
        assertThat(found.userId()).isEqualTo(userId);
        assertThat(found.transactionDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(found.amount()).isEqualTo(15_000L);
        assertThat(found.memo()).isEqualTo("메모");
    }

    @Test
    void softDelete_setsDeletedAt_andExcludesFromFindById() {
        FinanceTransaction saved = adapter.save(groupTransaction(CATEGORY_A, userId, LocalDate.now(), 1_000L));

        adapter.softDelete(saved.id());
        entityManager.clear();

        assertThat(adapter.findById(saved.id())).isEmpty();
        var deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM finance_transactions WHERE id = ?", java.sql.Timestamp.class, saved.id());
        assertThat(deletedAt).isNotNull();
    }

    @Test
    void findMyScope_categoryAndFilterUserIdFilters_matchExactCombination() {
        LocalDate targetDate = LocalDate.of(2026, 6, 15);
        FinanceTransaction target = adapter.save(groupTransaction(CATEGORY_A, userId, targetDate, 10_000L));

        adapter.save(groupTransaction(CATEGORY_B, userId, targetDate, 40_000L));      // 다른 카테고리
        adapter.save(groupTransaction(CATEGORY_A, otherUserId, targetDate, 50_000L)); // 다른 입력자

        var result = adapter.findMyScope(userId, groupId, null, null, CATEGORY_A, userId);

        assertThat(result).extracting(FinanceTransaction::id).containsExactly(target.id());
    }

    @Test
    void findMyScope_nullFilters_returnAllActiveInGroup() {
        adapter.save(groupTransaction(CATEGORY_A, userId, LocalDate.of(2026, 1, 1), 1_000L));
        adapter.save(groupTransaction(CATEGORY_B, otherUserId, LocalDate.of(2026, 2, 1), 2_000L));

        var result = adapter.findMyScope(userId, groupId, null, null, null, null);

        assertThat(result).hasSize(2);
    }

    // 회귀(플랜 항목 4): findMyScope는 (내 개인 데이터) ∪ (내 그룹 데이터)만 반환해야 하고, 무그룹 유저는
    // currentGroupId=null이라 개인 데이터만 조회돼야 한다 — 다른 사용자의 개인 데이터는 절대 섞이면 안 된다.
    @Test
    void findMyScope_returnsPersonalUnionGroup_excludingOthersPersonalData() {
        FinanceTransaction myPersonal = adapter.save(personalTransaction(CATEGORY_A, userId, LocalDate.of(2026, 5, 1), 1_000L));
        FinanceTransaction myGroup = adapter.save(groupTransaction(CATEGORY_A, userId, LocalDate.of(2026, 5, 2), 2_000L));
        FinanceTransaction othersPersonal = adapter.save(personalTransaction(CATEGORY_A, otherUserId, LocalDate.of(2026, 5, 3), 3_000L));

        var result = adapter.findMyScope(userId, groupId, null, null, null, null);

        assertThat(result).extracting(FinanceTransaction::id)
                .contains(myPersonal.id(), myGroup.id())
                .doesNotContain(othersPersonal.id());
    }

    @Test
    void findMyScope_noGroup_returnsOnlyPersonalData() {
        FinanceTransaction myPersonal = adapter.save(personalTransaction(CATEGORY_A, userId, LocalDate.of(2026, 5, 1), 1_000L));
        FinanceTransaction myGroup = adapter.save(groupTransaction(CATEGORY_A, userId, LocalDate.of(2026, 5, 2), 2_000L));

        var result = adapter.findMyScope(userId, null, null, null, null, null);

        assertThat(result).extracting(FinanceTransaction::id)
                .contains(myPersonal.id())
                .doesNotContain(myGroup.id());
    }
}
