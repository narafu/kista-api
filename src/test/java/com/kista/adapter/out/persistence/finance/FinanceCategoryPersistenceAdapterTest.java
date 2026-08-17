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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// §4.2 카테고리 소프트 삭제 dual-read-mode(findSelectableByGroup=필터, findById=무필터) 검증
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

    private FinanceCategory groupCategory(UUID parentId, FinanceCategory.Type type, String name) {
        return new FinanceCategory(null, groupId, parentId, userId, type, name, 0, null);
    }

    @Test
    void findSelectableByGroup_returnsSystemCategoriesUnionGroupOwnCategories_excludingSoftDeleted() {
        FinanceCategory active = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "활성카테고리"));
        FinanceCategory toDelete = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "삭제될카테고리"));
        adapter.softDeleteWithChildren(toDelete.id());

        var result = adapter.findSelectableByGroup(groupId, null);

        assertThat(result).extracting(FinanceCategory::id)
                .contains(SYSTEM_EXPENSE_L1, SYSTEM_INCOME_L1, active.id())
                .doesNotContain(toDelete.id());
    }

    @Test
    void findSelectableByGroup_withType_filtersByType() {
        FinanceCategory expenseCategory = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "소비카테고리"));
        FinanceCategory incomeCategory = adapter.save(groupCategory(null, FinanceCategory.Type.INCOME, "수입카테고리"));

        var result = adapter.findSelectableByGroup(groupId, FinanceCategory.Type.EXPENSE);

        assertThat(result).extracting(FinanceCategory::id)
                .contains(SYSTEM_EXPENSE_L1, expenseCategory.id())
                .doesNotContain(SYSTEM_INCOME_L1, incomeCategory.id());
        assertThat(result).allSatisfy(c -> assertThat(c.type()).isEqualTo(FinanceCategory.Type.EXPENSE));
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
        assertThat(found.get().type()).isEqualTo(FinanceCategory.Type.EXPENSE);

        // 도메인 레코드엔 deletedAt 필드가 없으므로 실제 소프트 삭제 여부는 raw 컬럼으로 확인한다
        var deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM finance_categories WHERE id = ?", java.sql.Timestamp.class, saved.id());
        assertThat(deletedAt).isNotNull();
    }

    @Test
    void softDeleteWithChildren_softDeletesTargetAndChildren() {
        FinanceCategory l1 = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "L1카테고리"));
        FinanceCategory l2 = adapter.save(groupCategory(l1.id(), FinanceCategory.Type.EXPENSE, "L2카테고리"));

        adapter.softDeleteWithChildren(l1.id());

        Integer deletedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM finance_categories WHERE id IN (?, ?) AND deleted_at IS NOT NULL",
                Integer.class, l1.id(), l2.id());
        assertThat(deletedCount).isEqualTo(2);

        assertThat(adapter.findById(l1.id())).isPresent();
        assertThat(adapter.findById(l2.id())).isPresent();
    }

    @Test
    void save_duplicateNameSameGroupAndParent_throwsDuplicateNameException() {
        FinanceCategory parent = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "부모카테고리"));
        adapter.save(groupCategory(parent.id(), FinanceCategory.Type.EXPENSE, "중복이름"));

        // uq_finance_categories_group_parent_name 위반은 마지막 DB 접촉이어야 한다 —
        // Postgres는 제약 위반 이후 같은 트랜잭션의 후속 명령을 전부 거부한다 (aborted transaction).
        assertThatThrownBy(() -> adapter.save(groupCategory(parent.id(), FinanceCategory.Type.EXPENSE, "중복이름")))
                .isInstanceOf(FinanceCategory.DuplicateNameException.class);
    }

    @Test
    void save_duplicateNameSameGroupBothL1_throwsDuplicateNameException() {
        // parentId=null(L1)끼리도 막혀야 한다 — V13의 uq_finance_categories_group_parent_name은
        // COALESCE(parent_id, sentinel-uuid)로 걸려 있어 Postgres UNIQUE 인덱스가 NULL을 서로 다른 값으로
        // 취급하는 문제(L1끼리는 안 막히던 실측 버그)를 우회한다. 이 테스트가 그 수정의 회귀 방지책이다.
        adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "중복L1이름"));

        assertThatThrownBy(() -> adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "중복L1이름")))
                .isInstanceOf(FinanceCategory.DuplicateNameException.class);
    }

    @Test
    void reassignGroup_movesCreatedByOwnedRowsToTargetGroup() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '그룹2', false, now(), now())",
                otherGroupId, userId);
        FinanceCategory category = adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "이관대상"));

        adapter.reassignGroup(groupId, otherGroupId, userId);

        FinanceCategory reassigned = adapter.findById(category.id()).orElseThrow();
        assertThat(reassigned.groupId()).isEqualTo(otherGroupId);
    }

    @Test
    void reassignGroup_nameCollisionInTargetGroup_throwsDuplicateNameException() {
        UUID otherGroupId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '그룹2', false, now(), now())",
                otherGroupId, userId);
        // 이관 대상 그룹에 같은 이름의 L1 카테고리가 이미 있다
        FinanceCategory conflicting = new FinanceCategory(null, otherGroupId, null, userId,
                FinanceCategory.Type.EXPENSE, "겹치는이름", 0, null);
        adapter.save(conflicting);
        adapter.save(groupCategory(null, FinanceCategory.Type.EXPENSE, "겹치는이름"));

        // reassignGroup 호출은 마지막 DB 접촉이어야 한다 (aborted transaction 규칙, 위 테스트들과 동일 이유)
        assertThatThrownBy(() -> adapter.reassignGroup(groupId, otherGroupId, userId))
                .isInstanceOf(FinanceCategory.DuplicateNameException.class);
    }
}
