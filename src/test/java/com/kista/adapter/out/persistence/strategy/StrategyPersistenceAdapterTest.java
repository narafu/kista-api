package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyInfiniteDetail;
import com.kista.domain.model.strategy.StrategyVersion;
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
    void save_infiniteStrategy_persistsCommonAndDetailRows() {
        Strategy strategy = new Strategy(
                null, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
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
    void deleteByStrategyId_removesInfiniteDetailRowOnly() {
        Strategy saved = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.TQQQ, Strategy.CycleSeedType.NONE
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

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V17__split_infinite_strategy_details.sql"));

        assertThat(migration).contains("INSERT INTO strategy_infinite_version (strategy_version_id, division_count, created_at, updated_at, deleted_at)");
        assertThat(migration).contains("JOIN strategy_version sv ON sv.strategy_id = s.id");
        assertThat(migration).contains("WHERE s.type = 'INFINITE';");
    }
}
