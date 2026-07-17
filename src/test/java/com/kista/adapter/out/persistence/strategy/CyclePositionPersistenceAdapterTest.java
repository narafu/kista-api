package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.CyclePosition;
import com.kista.domain.model.strategy.CyclePositionInfiniteDetail;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.support.DataJpaTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class CyclePositionPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
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
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
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
        entityManager.flush();

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
                "SELECT COUNT(*) FROM cycle_position_infinite WHERE cycle_position_id IN (?, ?) AND deleted_at IS NULL",
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

    // ===== findByUserAndRange — raw JDBC로 FK 체인·created_at·deleted_at을 직접 통제 =====
    // createdAt은 JPA auditing(@CreatedDate) 대상이라 어댑터 경유로는 값을 지정할 수 없어
    // OrderPersistenceAdapterDbTest와 동일하게 raw INSERT로 우회한다.

    private UUID insertUserAndAccount(boolean deletedAccount) {
        UUID uId = UUID.randomUUID();
        UUID accId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                uId, "kakao_" + uId, "ACTIVE", "USER");
        insertAccountForUser(accId, uId, deletedAccount);
        return accId;
    }

    private void insertAccountForUser(UUID accId, UUID ownerUserId, boolean deleted) {
        String accountNo = accId.toString().replace("-", "").substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now(), ?)",
                accId, ownerUserId, "테스트계좌-" + accId, "KIS", accountNo, "01", "key", "secret",
                deleted ? Timestamp.from(Instant.now()) : null);
    }

    // strategy → strategy_version → strategy_cycle 체인 삽입, cycleId 반환
    private UUID insertCycleChain(UUID accId, boolean deletedStrategy, boolean deletedCycle) {
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), now(), ?)",
                strategyId, accId, "INFINITE", "SOXL", "ACTIVE", "NONE",
                deletedStrategy ? Timestamp.from(Instant.now()) : null);
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, now(), ?)",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), LocalDate.now(),
                deletedCycle ? Timestamp.from(Instant.now()) : null);
        return cycleId;
    }

    private void insertPosition(UUID cycleId, BigDecimal usdDeposit, Instant createdAt, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO cycle_position (id, strategy_cycle_id, usd_deposit, holdings, created_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), cycleId, usdDeposit, 0,
                Timestamp.from(createdAt), deleted ? Timestamp.from(createdAt) : null);
    }

    @Test
    void findByUserAndRange_excludesOtherUsersPositions() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(1, ChronoUnit.HOURS);
        UUID myCycleId = insertCycleChain(accountId, false, false);
        insertPosition(myCycleId, new BigDecimal("111.00"), base, false);

        UUID otherAccountId = insertUserAndAccount(false);
        UUID otherCycleId = insertCycleChain(otherAccountId, false, false);
        insertPosition(otherCycleId, new BigDecimal("999.00"), base, false);

        List<CyclePosition> result = cyclePositionAdapter.findByUserAndRange(
                userId, base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.HOURS));

        assertThat(result).extracting(CyclePosition::usdDeposit)
                .containsExactly(new BigDecimal("111.00"));
    }

    @Test
    void findByUserAndRange_excludesSoftDeletedAtAnyChainLevel() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(1, ChronoUnit.HOURS);

        UUID activeCycleId = insertCycleChain(accountId, false, false);
        insertPosition(activeCycleId, new BigDecimal("1.00"), base, false); // 유일하게 살아있는 행

        insertPosition(activeCycleId, new BigDecimal("2.00"), base, true); // cp 자체 삭제

        UUID deletedCycleId = insertCycleChain(accountId, false, true); // sc 삭제
        insertPosition(deletedCycleId, new BigDecimal("3.00"), base, false);

        UUID deletedStrategyCycleId = insertCycleChain(accountId, true, false); // s 삭제
        insertPosition(deletedStrategyCycleId, new BigDecimal("4.00"), base, false);

        UUID deletedAccountId = UUID.randomUUID();
        insertAccountForUser(deletedAccountId, userId, true); // a 삭제
        UUID deletedAccountCycleId = insertCycleChain(deletedAccountId, false, false);
        insertPosition(deletedAccountCycleId, new BigDecimal("5.00"), base, false);

        List<CyclePosition> result = cyclePositionAdapter.findByUserAndRange(
                userId, base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.HOURS));

        assertThat(result).extracting(CyclePosition::usdDeposit)
                .containsExactly(new BigDecimal("1.00"));
    }

    @Test
    void findByUserAndRange_ordersAscendingAndRespectsRangeBoundaries() {
        Instant from = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(3, ChronoUnit.HOURS);
        Instant to = from.plus(3, ChronoUnit.HOURS);
        UUID cycleId = insertCycleChain(accountId, false, false);

        // 등록 순서를 뒤섞어 createdAt ASC 정렬이 insert 순서가 아님을 검증
        insertPosition(cycleId, new BigDecimal("30.00"), from.plus(2, ChronoUnit.HOURS), false);
        insertPosition(cycleId, new BigDecimal("10.00"), from, false); // 하한 경계 포함 (>= from)
        insertPosition(cycleId, new BigDecimal("20.00"), from.plus(1, ChronoUnit.HOURS), false);

        // 경계 밖 — 제외되어야 함
        insertPosition(cycleId, new BigDecimal("9.00"), from.minus(1, ChronoUnit.MILLIS), false); // from 직전
        insertPosition(cycleId, new BigDecimal("40.00"), to, false); // 상한 경계는 제외 (< to)

        List<CyclePosition> result = cyclePositionAdapter.findByUserAndRange(userId, from, to);

        assertThat(result).extracting(CyclePosition::usdDeposit)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"));
    }
}
