package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.CyclePositionInfiniteDetail;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyVersionPersistenceAdapter.class,
        StrategyCyclePersistenceAdapter.class,
        CyclePositionPersistenceAdapter.class,
        CyclePositionInfiniteDetailPersistenceAdapter.class
})
class CyclePositionPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StrategyPersistenceAdapter strategyAdapter;
    @Autowired StrategyVersionPersistenceAdapter strategyVersionAdapter;
    @Autowired StrategyCyclePersistenceAdapter strategyCycleAdapter;
    @Autowired CyclePositionPersistenceAdapter cyclePositionAdapter;
    @Autowired CyclePositionInfiniteDetailPersistenceAdapter cyclePositionInfiniteDetailAdapter;

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
                "INSERT INTO accounts (id, user_id, nickname, account_no, app_key, secret_key, kis_account_type, broker, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "74420614", "key", "secret", "01", "KIS");
    }

    @Test
    void save_infinitePosition_persistsCommonAndDetailRows() {
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
        ));
        StrategyVersion version = strategyVersionAdapter.save(new StrategyVersion(null, strategy.id(), 1, null, null));
        StrategyCycle cycle = strategyCycleAdapter.save(new StrategyCycle(
                null, strategy.id(), version.id(), new BigDecimal("1000.00"),
                null, LocalDate.now(), null, null, null
        ));

        CyclePosition saved = cyclePositionAdapter.save(
                CyclePosition.initialSnapshot(cycle.id(), new BigDecimal("1000.00"))
        );
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(saved.id(), true));

        Integer positionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cycle_position WHERE id = ?",
                Integer.class,
                saved.id());
        Integer detailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cycle_position_infinite WHERE cycle_position_id = ? AND is_reverse_mode = true",
                Integer.class,
                saved.id());
        Integer auditedRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cycle_position_infinite WHERE cycle_position_id = ? AND created_at IS NOT NULL AND deleted_at IS NULL",
                Integer.class,
                saved.id());

        assertThat(saved.id()).isNotNull();
        assertThat(positionRows).isEqualTo(1);
        assertThat(detailRows).isEqualTo(1);
        assertThat(auditedRows).isEqualTo(1);
        assertThat(cyclePositionInfiniteDetailAdapter.findByCyclePositionId(saved.id()))
                .contains(new CyclePositionInfiniteDetail(saved.id(), true));
    }

    @Test
    void findLatestByCycleId_andDeleteByStrategyId_followPersistedRows() {
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.INFINITE,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
        ));
        StrategyVersion version = strategyVersionAdapter.save(new StrategyVersion(null, strategy.id(), 1, null, null));
        StrategyCycle cycle = strategyCycleAdapter.save(new StrategyCycle(
                null, strategy.id(), version.id(), new BigDecimal("1000.00"),
                null, LocalDate.now(), null, null, null
        ));

        CyclePosition older = cyclePositionAdapter.save(
                CyclePosition.initialSnapshot(cycle.id(), new BigDecimal("1000.00"))
        );
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(older.id(), false));
        CyclePosition newer = cyclePositionAdapter.save(new CyclePosition(
                null, cycle.id(), new BigDecimal("900.00"),
                new BigDecimal("25.00"), new BigDecimal("24.00"), 5, null, null
        ));
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(newer.id(), true));

        List<CyclePositionInfiniteDetail> latest = cyclePositionInfiniteDetailAdapter.findLatestByCycleId(cycle.id(), 2);

        assertThat(latest)
                .extracting(CyclePositionInfiniteDetail::cyclePositionId)
                .containsExactly(newer.id(), older.id());
        assertThat(latest)
                .extracting(CyclePositionInfiniteDetail::isReverseMode)
                .containsExactly(true, false);

        cyclePositionInfiniteDetailAdapter.deleteByStrategyId(strategy.id());

        Integer detailRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cycle_position_infinite WHERE cycle_position_id IN (?, ?)",
                Integer.class,
                older.id(), newer.id());
        Integer positionRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM cycle_position WHERE id IN (?, ?)",
                Integer.class,
                older.id(), newer.id());

        assertThat(detailRows).isZero();
        assertThat(positionRows).isEqualTo(2);
    }

    @Test
    void cyclePositionInfiniteSchemaAndMigration_followAuditConventionAndKeepDeletedHistory() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'cycle_position_infinite'
                ORDER BY ordinal_position
                """, String.class))
                .containsExactly("cycle_position_id", "is_reverse_mode", "created_at", "deleted_at");

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V17__split_infinite_strategy_details.sql"));

        assertThat(migration).contains("INSERT INTO cycle_position_infinite (cycle_position_id, is_reverse_mode, created_at, deleted_at)");
        assertThat(migration).contains("SELECT id, is_reverse_mode, created_at, deleted_at");
        assertThat(migration).doesNotContain("FROM cycle_position\nWHERE deleted_at IS NULL;");
    }
}
