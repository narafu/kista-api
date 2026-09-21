package com.kista.trading.adapter.out.persistence;

import com.kista.trading.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyInfiniteDetail;
import com.kista.trading.domain.model.StrategyVersion;
import com.kista.support.DataJpaTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyInfiniteDetailPersistenceAdapter.class,
        StrategyVersionPersistenceAdapter.class
})
class StrategyPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired StrategyPersistenceAdapter strategyAdapter;
    @Autowired StrategyInfiniteDetailPersistenceAdapter strategyInfiniteDetailAdapter;
    @Autowired StrategyVersionPersistenceAdapter strategyVersionAdapter;

    private UUID userId;
    private UUID accountId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
    }

    @Test
    void findAllActive_usesNotifyProfileActiveFlag_notUsersTable() {
        // users.status는 REJECTED지만 프로필이 active → 포함돼야 한다(users 비의존 증명)
        Strategy included = saveActiveStrategy(insertAccountOfUser("REJECTED", Boolean.TRUE), StrategyTicker.SOXL);
        // users는 ACTIVE지만 프로필 inactive → 제외
        saveActiveStrategy(insertAccountOfUser("ACTIVE", Boolean.FALSE), StrategyTicker.SOXL);
        // 프로필 행 자체가 없음 → 제외
        saveActiveStrategy(insertAccountOfUser("ACTIVE", null), StrategyTicker.SOXL);
        // 활성 프로필이지만 계좌 소프트 삭제 → 제외
        UUID deletedAccountId = insertAccountOfUser("ACTIVE", Boolean.TRUE);
        saveActiveStrategy(deletedAccountId, StrategyTicker.SOXL);
        jdbcTemplate.update("UPDATE accounts SET deleted_at = now() WHERE id = ?", deletedAccountId);
        // 활성 프로필이지만 PAUSED 전략 → 제외
        strategyAdapter.save(new Strategy(null, insertAccountOfUser("ACTIVE", Boolean.TRUE), StrategyType.INFINITE,
                StrategyStatus.PAUSED, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        entityManager.flush();
        entityManager.clear();

        assertThat(strategyAdapter.findAllActive()).extracting(Strategy::id).containsExactly(included.id());
    }

    // 사용자+계좌 삽입 후 accountId 반환. profileActive=null이면 프로필 행을 만들지 않는다
    private UUID insertAccountOfUser(String usersStatus, Boolean profileActive) {
        UUID uid = UUID.randomUUID();
        UUID aid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                uid, "kakao_" + uid, usersStatus, "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                aid, uid, "계좌", "KIS", "74420614" + aid, "01", "key", "secret");
        if (profileActive != null) {
            jdbcTemplate.update(
                    "INSERT INTO kista.user_notify_profile (user_id, notification_prefs, balance_check_enabled, is_active, updated_at) VALUES (?, '{}', TRUE, ?, now())",
                    uid, profileActive);
        }
        return aid;
    }

    private Strategy saveActiveStrategy(UUID acctId, StrategyTicker ticker) {
        return strategyAdapter.save(new Strategy(null, acctId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, ticker, StrategyCycleSeedType.NONE));
    }

    @Test
    void save_infiniteStrategy_persistsCommonAndDetailRows() {
        Strategy strategy = new Strategy(
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
        );

        Strategy saved = strategyAdapter.save(strategy);
        StrategyVersion version = strategyVersionAdapter.save(new StrategyVersion(null, saved.id(), 1, null, null));
        strategyInfiniteDetailAdapter.save(new StrategyInfiniteDetail(version.id(), 20));
        entityManager.flush();

        Integer strategyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy WHERE id = ?",
                Integer.class,
                saved.id());
        Integer detailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy_infinite_version WHERE strategy_version_id = ? AND division_count = 20",
                Integer.class,
                version.id());
        Integer auditedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy_infinite_version WHERE strategy_version_id = ? AND created_at IS NOT NULL AND updated_at IS NOT NULL AND deleted_at IS NULL",
                Integer.class,
                version.id());

        assertThat(saved.id()).isNotNull();
        assertThat(strategyRows).isEqualTo(1);
        assertThat(detailRows).isEqualTo(1);
        assertThat(auditedRows).isEqualTo(1);
        assertThat(strategyInfiniteDetailAdapter.findByStrategyVersionId(version.id()))
                .contains(new StrategyInfiniteDetail(version.id(), 20));
        assertThat(strategyInfiniteDetailAdapter.findActiveByStrategyId(saved.id()))
                .contains(new StrategyInfiniteDetail(version.id(), 20));
    }

    @Test
    void findByStrategyVersionIds_returnsDetailsKeyedByVersionId() {
        Strategy strategyA = strategyAdapter.save(new Strategy(
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        Strategy strategyB = strategyAdapter.save(new Strategy(
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE));
        StrategyVersion versionA = strategyVersionAdapter.save(new StrategyVersion(null, strategyA.id(), 1, null, null));
        StrategyVersion versionB = strategyVersionAdapter.save(new StrategyVersion(null, strategyB.id(), 1, null, null));
        strategyInfiniteDetailAdapter.save(new StrategyInfiniteDetail(versionA.id(), 20));
        strategyInfiniteDetailAdapter.save(new StrategyInfiniteDetail(versionB.id(), 30));

        var result = strategyInfiniteDetailAdapter.findByStrategyVersionIds(
                java.util.List.of(versionA.id(), versionB.id(), UUID.randomUUID()));

        assertThat(result).hasSize(2);
        assertThat(result.get(versionA.id()).divisionCount()).isEqualTo(20);
        assertThat(result.get(versionB.id()).divisionCount()).isEqualTo(30);
    }

    @Test
    void findByStrategyVersionIds_emptyCollection_returnsEmptyMap() {
        assertThat(strategyInfiniteDetailAdapter.findByStrategyVersionIds(java.util.List.of())).isEmpty();
    }

    @Test
    void deleteByStrategyId_removesInfiniteDetailRowOnly() {
        Strategy saved = strategyAdapter.save(new Strategy(
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.TQQQ, StrategyCycleSeedType.NONE
        ));
        StrategyVersion version = strategyVersionAdapter.save(new StrategyVersion(null, saved.id(), 1, null, null));
        strategyInfiniteDetailAdapter.save(new StrategyInfiniteDetail(version.id(), 30));

        strategyInfiniteDetailAdapter.deleteByStrategyId(saved.id());
        entityManager.flush();
        entityManager.clear();

        Integer strategyRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy WHERE id = ?",
                Integer.class,
                saved.id());
        Integer activeDetailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy_infinite_version WHERE strategy_version_id = ? AND deleted_at IS NULL",
                Integer.class,
                version.id());
        Integer totalDetailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy_infinite_version WHERE strategy_version_id = ?",
                Integer.class,
                version.id());

        assertThat(strategyRows).isEqualTo(1);
        assertThat(activeDetailRows).isZero();
        assertThat(totalDetailRows).isEqualTo(1);
        assertThat(strategyInfiniteDetailAdapter.findByStrategyVersionId(version.id())).isEmpty();
        assertThat(strategyInfiniteDetailAdapter.findActiveByStrategyId(saved.id())).isEmpty();
    }

    @Test
    void strategyInfiniteSchemaAndMigration_followAuditConventionAndKeepDeletedHistory() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'strategy_infinite_version'
                ORDER BY ordinal_position
                """, String.class))
                .containsExactly("strategy_version_id", "division_count", "created_at", "updated_at", "deleted_at");

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__init.sql"));

        assertThat(migration).contains("CREATE TABLE strategy_infinite_version");
        assertThat(migration).contains("division_count      INTEGER     NOT NULL");
        assertThat(migration).contains("deleted_at          TIMESTAMPTZ");
    }
}
