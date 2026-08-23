package com.kista.adapter.out.persistence.finance;

import com.kista.domain.model.finance.AssetClass;
import com.kista.domain.model.finance.AssetSnapshot;
import com.kista.domain.model.finance.Market;
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

@Import(AssetSnapshotPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class AssetSnapshotPersistenceAdapterTest extends DataJpaTestBase {

    // V13 시드값 — 시스템 카테고리 (ASSET L1) '투자'
    private static final UUID CATEGORY_ASSET = UUID.fromString("f1000000-0000-4000-8000-000000000403");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired AssetSnapshotPersistenceAdapter adapter;

    private UUID userId;
    private UUID otherUserId;
    private UUID groupId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        groupId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                otherUserId, "kakao_" + otherUserId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, created_at, updated_at) VALUES (?, ?, now(), now())",
                groupId, userId);
        jdbcTemplate.update(
                "INSERT INTO finance_accounts (id, group_id, user_id, account_type, name, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SECURITIES', '테스트증권계좌', now(), now())",
                accountId, groupId, userId);
    }

    private AssetSnapshot groupSnapshot(UUID accId, UUID owner) {
        return new AssetSnapshot(null, groupId, CATEGORY_ASSET, accId, owner, LocalDate.of(2026, 8, 1),
                AssetClass.EQUITY, Market.GLOBAL, "적립식", "비상금 별도 관리", 1_000_000L, null);
    }

    private AssetSnapshot personalSnapshot(UUID owner) {
        return new AssetSnapshot(null, null, CATEGORY_ASSET, null, owner, LocalDate.of(2026, 8, 1),
                AssetClass.CASH, Market.DOMESTIC, null, null, 500_000L, null);
    }

    @Test
    void save_andFindById_roundTrips_withNullAccountId() {
        // 계좌 없는 자산(전세임차보증금 등) — accountId null 케이스
        AssetSnapshot saved = adapter.save(groupSnapshot(null, userId));

        AssetSnapshot found = adapter.findById(saved.id()).orElseThrow();

        assertThat(found.groupId()).isEqualTo(groupId);
        assertThat(found.categoryId()).isEqualTo(CATEGORY_ASSET);
        assertThat(found.accountId()).isNull();
        assertThat(found.assetClass()).isEqualTo(AssetClass.EQUITY);
        assertThat(found.market()).isEqualTo(Market.GLOBAL);
        assertThat(found.strategy()).isEqualTo("적립식");
        assertThat(found.memo()).isEqualTo("비상금 별도 관리");
        assertThat(found.amount()).isEqualTo(1_000_000L);
    }

    @Test
    void save_andFindById_roundTrips_withAccountId() {
        AssetSnapshot saved = adapter.save(groupSnapshot(accountId, userId));

        AssetSnapshot found = adapter.findById(saved.id()).orElseThrow();

        assertThat(found.accountId()).isEqualTo(accountId);
    }

    @Test
    void softDelete_setsDeletedAt_andExcludesFromFindById() {
        AssetSnapshot saved = adapter.save(groupSnapshot(null, userId));

        adapter.softDelete(saved.id());
        entityManager.clear();

        assertThat(adapter.findById(saved.id())).isEmpty();
        var deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM finance_asset_snapshots WHERE id = ?", java.sql.Timestamp.class, saved.id());
        assertThat(deletedAt).isNotNull();
    }

    // 회귀(플랜 항목 4): findMyScope는 (내 개인) ∪ (내 그룹)만 반환 — 타인의 개인 자산 기록은 유출되면 안 된다.
    @Test
    void findMyScope_returnsPersonalUnionGroup_excludingOthersPersonalData() {
        AssetSnapshot myPersonal = adapter.save(personalSnapshot(userId));
        AssetSnapshot myGroup = adapter.save(groupSnapshot(null, userId));
        AssetSnapshot othersPersonal = adapter.save(personalSnapshot(otherUserId));

        var result = adapter.findMyScope(userId, groupId, null, null, null);

        assertThat(result).extracting(AssetSnapshot::id)
                .contains(myPersonal.id(), myGroup.id())
                .doesNotContain(othersPersonal.id());
    }

    @Test
    void findMyScope_noGroup_returnsOnlyPersonalData() {
        AssetSnapshot myPersonal = adapter.save(personalSnapshot(userId));
        AssetSnapshot myGroup = adapter.save(groupSnapshot(null, userId));

        var result = adapter.findMyScope(userId, null, null, null, null);

        assertThat(result).extracting(AssetSnapshot::id)
                .contains(myPersonal.id())
                .doesNotContain(myGroup.id());
    }

    @Test
    void softDeleteByUserId_softDeletesOnlyThatUsersOwnedSnapshots() {
        AssetSnapshot mine = adapter.save(personalSnapshot(userId));
        AssetSnapshot others = adapter.save(personalSnapshot(otherUserId));

        adapter.softDeleteByUserId(userId);
        entityManager.clear();

        assertThat(adapter.findById(mine.id())).isEmpty();
        assertThat(adapter.findById(others.id())).isPresent();
    }
}
