package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.FinanceCategory;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 3-state 소유축(system/personal/group) 조회·소프트삭제 dual-read-mode 검증
@Import(FinanceCategoryPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class FinanceCategoryPersistenceAdapterTest extends DataJpaTestBase {

    // V13 시드값 — 시스템 카테고리 (EXPENSE L1 '주거비')
    private static final UUID SYSTEM_EXPENSE_L1 = UUID.fromString("f1000000-0000-4000-8000-000000000201");
    // V13 시드값 — 시스템 카테고리 (INCOME L1 '근로소득')
    private static final UUID SYSTEM_INCOME_L1 = UUID.fromString("f1000000-0000-4000-8000-000000000101");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired FinanceCategoryPersistenceAdapter adapter;

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

    private FinanceCategory personalCategory(UUID owner, FinanceCategory.Type type, String name) {
        return new FinanceCategory(null, null, null, owner, type, name, 0, null);
    }

    private FinanceCategory groupCategory(UUID parentId, FinanceCategory.Type type, String name) {
        return new FinanceCategory(null, groupId, parentId, userId, type, name, 0, null);
    }

    @Test
    void findSelectable_returnsSystemUnionMyPersonalUnionMyGroup_excludingSoftDeleted() {
        FinanceCategory personal = adapter.save(personalCategory(userId, FinanceCategory.Type.EXPENSE, "개인카테고리"));
        FinanceCategory group = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "그룹카테고리"));
        FinanceCategory toDelete = adapter.save(personalCategory(userId, FinanceCategory.Type.EXPENSE, "삭제될카테고리"));
        adapter.softDeleteWithChildren(toDelete.id());

        var result = adapter.findSelectable(userId, groupId, null);

        assertThat(result).extracting(FinanceCategory::id)
                .contains(SYSTEM_EXPENSE_L1, SYSTEM_INCOME_L1, personal.id(), group.id())
                .doesNotContain(toDelete.id());
    }

    // 회귀 방지: 타 사용자의 개인 카테고리(userId=B, groupId=null)가 findSelectable(A, ...)에 유출되면 안 된다.
    // "(group_id IS NULL OR group_id = :groupId)" 형태의 쿼리는 이 유출을 일으키므로 3-way 쿼리를 강제한다.
    @Test
    void findSelectable_doesNotLeakOtherUsersPersonalCategories() {
        FinanceCategory otherPersonal = adapter.save(personalCategory(otherUserId, FinanceCategory.Type.EXPENSE, "타인개인카테고리"));

        var result = adapter.findSelectable(userId, null, null);

        assertThat(result).extracting(FinanceCategory::id).doesNotContain(otherPersonal.id());
    }

    @Test
    void findSelectable_withType_filtersByType() {
        FinanceCategory expenseCategory = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "소비카테고리"));
        FinanceCategory incomeCategory = adapter.save(groupCategory(null, FinanceCategory.Type.INCOME, "수입카테고리"));

        var result = adapter.findSelectable(userId, groupId, FinanceCategory.Type.EXPENSE);

        assertThat(result).extracting(FinanceCategory::id)
                .contains(SYSTEM_EXPENSE_L1, expenseCategory.id())
                .doesNotContain(SYSTEM_INCOME_L1, incomeCategory.id());
        assertThat(result).allSatisfy(c -> assertThat(c.type()).isEqualTo(FinanceCategory.Type.EXPENSE));
    }

    @Test
    void findSelectable_withNullUserAndGroup_returnsOnlySystemCategories() {
        adapter.save(personalCategory(userId, FinanceCategory.Type.EXPENSE, "개인전용카테고리"));

        var result = adapter.findSelectable(null, null, null);

        assertThat(result).extracting(FinanceCategory::id).contains(SYSTEM_EXPENSE_L1, SYSTEM_INCOME_L1);
        assertThat(result).allSatisfy(c -> assertThat(c.userId()).isNull());
    }

    @Test
    void findById_onSoftDeletedCategory_stillReturnsIt() {
        // §4.2 — 클래스 레벨 @SQLRestriction을 의도적으로 붙이지 않은 설계의 핵심 회귀 테스트.
        // 미래에 누군가 FinanceCategoryEntity에 @SQLRestriction("deleted_at IS NULL")을 추가하면
        // 이 테스트가 즉시 실패해 과거 거래/자산 렌더링이 깨지는 것을 막는다.
        FinanceCategory saved = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "삭제예정카테고리"));

        adapter.softDeleteWithChildren(saved.id());

        var found = adapter.findById(saved.id());
        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(saved.id());
        assertThat(found.get().groupId()).isEqualTo(groupId);
        assertThat(found.get().name()).isEqualTo("삭제예정카테고리");

        var deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM finance_categories WHERE id = ?", java.sql.Timestamp.class, saved.id());
        assertThat(deletedAt).isNotNull();
    }

    @Test
    void findActiveById_onSoftDeletedCategory_returnsEmpty() {
        FinanceCategory saved = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "삭제예정카테고리"));

        adapter.softDeleteWithChildren(saved.id());

        assertThat(adapter.findActiveById(saved.id())).isEmpty();
        assertThat(adapter.findById(saved.id())).isPresent();
    }

    @Test
    void softDeleteWithChildren_cascadesThroughArbitraryDepth() {
        FinanceCategory l1 = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "L1카테고리"));
        FinanceCategory l2 = adapter.save(groupCategory(l1.id(), FinanceCategory.Type.EXPENSE, "L2카테고리"));
        FinanceCategory l3 = adapter.save(groupCategory(l2.id(), FinanceCategory.Type.EXPENSE, "L3카테고리"));

        adapter.softDeleteWithChildren(l1.id());

        Integer deletedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_categories WHERE id IN (?, ?, ?) AND deleted_at IS NOT NULL",
                Integer.class, l1.id(), l2.id(), l3.id());
        assertThat(deletedCount).isEqualTo(3);
    }

    @Test
    void save_systemCategory_userIdNull_persists() {
        FinanceCategory systemCategory = new FinanceCategory(null, null, null, null,
                FinanceCategory.Type.EXPENSE, "새시스템카테고리", 50, null);

        FinanceCategory saved = adapter.save(systemCategory);

        assertThat(saved.groupId()).isNull();
        assertThat(saved.userId()).isNull();
        FinanceCategory found = adapter.findById(saved.id()).orElseThrow();
        assertThat(found.groupId()).isNull();
        assertThat(found.name()).isEqualTo("새시스템카테고리");
    }

    @Test
    void save_personalCategory_groupIdNullUserIdSet_persists() {
        FinanceCategory saved = adapter.save(personalCategory(userId, FinanceCategory.Type.EXPENSE, "개인카테고리"));

        assertThat(saved.groupId()).isNull();
        assertThat(saved.userId()).isEqualTo(userId);
    }

    @Test
    void softDeleteByUserId_softDeletesOnlyThatUsersOwnedCategories() {
        FinanceCategory mine = adapter.save(personalCategory(userId, FinanceCategory.Type.EXPENSE, "내카테고리"));
        FinanceCategory others = adapter.save(personalCategory(otherUserId, FinanceCategory.Type.EXPENSE, "타인카테고리"));

        adapter.softDeleteByUserId(userId);

        assertThat(adapter.findActiveById(mine.id())).isEmpty();
        assertThat(adapter.findActiveById(others.id())).isPresent();
    }
}
