package com.kista.api.finance.adapter.out.persistence;

import com.kista.shared.support.DataJpaTestBase;
import com.kista.api.finance.domain.model.FinanceTransaction;
import com.kista.api.finance.domain.model.FinanceTransactionCursor;
import com.kista.api.finance.domain.model.FinanceTransactionPageQuery;
import com.kista.api.finance.domain.model.FinanceTransactionPageRow;
import com.kista.api.finance.domain.model.FinanceTransactionSort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import({FinanceTransactionPersistenceAdapter.class})
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
                "INSERT INTO finance_groups (id, created_at, updated_at) VALUES (?, now(), now())",
                groupId);
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

    // ===== findPage =====

    // 같은 이름("교통") 카테고리 둘 — 소유자만 다르다
    private UUID insertCategory(UUID owner, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO finance_categories (id, user_id, type, name, sort_order, created_at, updated_at) "
                + "VALUES (?, ?, 'EXPENSE', ?, 0, now(), now())", id, owner, name);
        return id;
    }

    private FinanceTransaction tx(UUID categoryId, UUID owner, String date, long amount, String memo) {
        return adapter.save(new FinanceTransaction(null, null, categoryId, owner, LocalDate.parse(date), amount, memo, null));
    }

    private FinanceTransactionPageQuery pageQuery(FinanceTransactionSort sort, FinanceTransactionCursor cursor, int limit) {
        return new FinanceTransactionPageQuery(userId, null, null, null, null, null, null, sort, cursor, limit);
    }

    private static String sortValue(FinanceTransactionSort.Key key, FinanceTransactionPageRow r) {
        FinanceTransaction t = r.transaction();
        return switch (key) {
            case TRANSACTION_DATE -> t.transactionDate().toString();
            case AMOUNT -> String.valueOf(t.amount());
            case MEMO -> t.memo() == null || t.memo().isEmpty() ? null : t.memo();
            case CATEGORY -> r.categoryName();
        };
    }

    @Test
    void findPage_keysetBoundaries_everySortKeyAndDirection() {
        UUID traffic1 = insertCategory(userId, "교통");
        UUID traffic2 = insertCategory(otherUserId, "교통");
        // 같은 날짜·같은 금액·같은 카테고리 이름·null/빈 메모가 섞인 데이터
        tx(CATEGORY_A, userId, "2026-08-01", 1000, "a");
        tx(CATEGORY_A, userId, "2026-08-01", 1000, null);
        tx(CATEGORY_B, userId, "2026-08-01", 2000, "");
        tx(traffic1, userId, "2026-08-02", 2000, "b");
        tx(traffic2, userId, "2026-08-02", 3000, "a");
        tx(traffic1, userId, "2026-08-03", 3000, null);
        tx(CATEGORY_B, userId, "2026-08-03", 500, "c");
        tx(CATEGORY_A, userId, "2026-08-04", 500, "");
        tx(traffic2, userId, "2026-08-04", 500, "b");
        tx(CATEGORY_B, userId, "2026-08-05", 1000, null);
        entityManager.flush();
        entityManager.clear();

        for (FinanceTransactionSort.Key key : FinanceTransactionSort.Key.values()) {
            for (boolean asc : new boolean[]{true, false}) {
                FinanceTransactionSort sort = new FinanceTransactionSort(key, asc);
                // 전체 기대 순서 — 값 없음 맨 뒤, 동순위는 id를 같은 방향
                List<FinanceTransactionPageRow> all = adapter.findPage(pageQuery(sort, null, 100));
                Comparator<FinanceTransactionPageRow> byValue = Comparator.comparing(r -> sortValue(key, r),
                        Comparator.nullsLast(key == FinanceTransactionSort.Key.AMOUNT
                                ? Comparator.comparing(Long::parseLong) : Comparator.<String>naturalOrder()));
                Comparator<FinanceTransactionPageRow> byId = Comparator.comparing(r -> r.transaction().id().toString());
                Comparator<FinanceTransactionPageRow> expected = (a, b) -> {
                    boolean aNull = sortValue(key, a) == null;
                    boolean bNull = sortValue(key, b) == null;
                    if (aNull != bNull) return aNull ? 1 : -1;
                    int c = aNull ? 0 : byValue.compare(a, b) * (asc ? 1 : -1);
                    return c != 0 ? c : byId.compare(a, b) * (asc ? 1 : -1);
                };
                assertThat(all).hasSize(10).isSortedAccordingTo(expected);

                // size 2로 끝까지 — 중복·누락 없이 같은 순서여야 한다
                List<UUID> paged = new ArrayList<>();
                FinanceTransactionCursor cursor = null;
                while (true) {
                    List<FinanceTransactionPageRow> fetched = adapter.findPage(pageQuery(sort, cursor, 3));
                    List<FinanceTransactionPageRow> page = fetched.subList(0, Math.min(2, fetched.size()));
                    page.forEach(r -> paged.add(r.transaction().id()));
                    if (fetched.size() <= 2) break;
                    FinanceTransactionPageRow last = page.getLast();
                    cursor = new FinanceTransactionCursor(sort, sortValue(key, last), last.transaction().id());
                }
                assertThat(paged).as(sort.wire()).hasSize(10).doesNotHaveDuplicates()
                        .containsExactlyElementsOf(all.stream().map(r -> r.transaction().id()).toList());
            }
        }
    }

    @Test
    void findPage_filters() {
        FinanceTransaction personal = tx(CATEGORY_A, userId, "2026-08-10", 100, "Lunch Box");
        FinanceTransaction other = tx(CATEGORY_B, otherUserId, "2026-08-20", 200, "dinner");
        FinanceTransaction grouped = adapter.save(new FinanceTransaction(null, groupId, CATEGORY_B, otherUserId,
                LocalDate.of(2026, 9, 1), 300, "group", null));
        tx(CATEGORY_A, otherUserId, "2026-08-11", 400, "invisible"); // 남의 개인 거래
        entityManager.flush();
        entityManager.clear();
        FinanceTransactionSort sort = FinanceTransactionSort.DEFAULT;

        // 스코프: 개인 ∪ 그룹(그룹 없으면 개인만)
        assertThat(adapter.findPage(new FinanceTransactionPageQuery(userId, groupId, null, null, null, null, null, sort, null, 10)))
                .extracting(r -> r.transaction().id()).containsExactlyInAnyOrder(personal.id(), grouped.id());
        assertThat(adapter.findPage(pageQuery(sort, null, 10))).extracting(r -> r.transaction().id()).containsExactly(personal.id());

        // 그룹 스코프에서 from/to
        FinanceTransactionPageQuery scoped = new FinanceTransactionPageQuery(userId, groupId, LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31), null, null, null, sort, null, 10);
        assertThat(adapter.findPage(scoped)).extracting(r -> r.transaction().id()).containsExactly(personal.id());

        // categoryIds / filterUserId / memo(대소문자 무시)
        assertThat(adapter.findPage(new FinanceTransactionPageQuery(userId, groupId, null, null, Set.of(CATEGORY_B), null, null, sort, null, 10)))
                .extracting(r -> r.transaction().id()).containsExactly(grouped.id());
        assertThat(adapter.findPage(new FinanceTransactionPageQuery(userId, groupId, null, null, null, otherUserId, null, sort, null, 10)))
                .extracting(r -> r.transaction().id()).containsExactly(grouped.id());
        assertThat(adapter.findPage(new FinanceTransactionPageQuery(userId, groupId, null, null, null, null, "LUNCH b", sort, null, 10)))
                .extracting(r -> r.transaction().id()).containsExactly(personal.id());
        assertThat(other.id()).isNotNull();
    }
}
