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
    private UUID groupId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        groupId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO finance_groups (id, owner_user_id, name, personal, created_at, updated_at) VALUES (?, ?, '개인', true, now(), now())",
                groupId, userId);
        jdbcTemplate.update(
                "INSERT INTO finance_accounts (id, group_id, created_by, account_type, name, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SECURITIES', '테스트증권계좌', now(), now())",
                accountId, groupId, userId);
    }

    private AssetSnapshot snapshot(UUID accId) {
        return new AssetSnapshot(null, groupId, CATEGORY_ASSET, accId, userId, LocalDate.of(2026, 8, 1),
                AssetClass.EQUITY, Market.GLOBAL, "적립식", 1_000_000L, null);
    }

    @Test
    void save_andFindById_roundTrips_withNullAccountId() {
        // 계좌 없는 자산(전세임차보증금 등) — accountId null 케이스
        AssetSnapshot saved = adapter.save(snapshot(null));

        AssetSnapshot found = adapter.findById(saved.id()).orElseThrow();

        assertThat(found.groupId()).isEqualTo(groupId);
        assertThat(found.categoryId()).isEqualTo(CATEGORY_ASSET);
        assertThat(found.accountId()).isNull();
        assertThat(found.assetClass()).isEqualTo(AssetClass.EQUITY);
        assertThat(found.market()).isEqualTo(Market.GLOBAL);
        assertThat(found.strategy()).isEqualTo("적립식");
        assertThat(found.amount()).isEqualTo(1_000_000L);
    }

    @Test
    void save_andFindById_roundTrips_withAccountId() {
        AssetSnapshot saved = adapter.save(snapshot(accountId));

        AssetSnapshot found = adapter.findById(saved.id()).orElseThrow();

        assertThat(found.accountId()).isEqualTo(accountId);
    }

    @Test
    void softDelete_setsDeletedAt_andExcludesFromFindById() {
        AssetSnapshot saved = adapter.save(snapshot(null));

        adapter.softDelete(saved.id());
        // softDelete는 @Modifying 벌크 UPDATE라 1차 캐시를 자동 갱신하지 않는다 —
        // 같은 영속성 컨텍스트 안에서 방금 save()로 로드된 엔티티가 그대로 남아있으므로
        // clear()로 비워야 findById가 DB의 최신 상태를 다시 읽는다.
        entityManager.clear();

        assertThat(adapter.findById(saved.id())).isEmpty();
        var deletedAt = jdbcTemplate.queryForObject(
                "SELECT deleted_at FROM finance_asset_snapshots WHERE id = ?", java.sql.Timestamp.class, saved.id());
        assertThat(deletedAt).isNotNull();
    }
}
