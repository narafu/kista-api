package com.kista.trading.adapter.out.persistence;

import com.kista.strategyconfig.adapter.out.persistence.StrategyPersistenceAdapter;
import com.kista.trading.domain.model.CyclePosition;
import com.kista.trading.domain.model.CyclePositionInfiniteDetail;
import com.kista.strategyconfig.domain.model.Strategy;
import com.kista.trading.domain.model.StrategyCycle;
import com.kista.trading.domain.model.StrategyVersion;
import com.kista.support.DataJpaTestBase;
import com.kista.trading.application.port.output.StrategyLookupPort;
import com.kista.trading.domain.model.StrategyRef;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Collection;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyVersionPersistenceAdapter.class,
        StrategyCyclePersistenceAdapter.class,
        CyclePositionPersistenceAdapter.class,
        CyclePositionInfiniteDetailPersistenceAdapter.class,
        CyclePositionPersistenceAdapterTest.StrategyLookupTestConfig.class
})
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class CyclePositionPersistenceAdapterTest extends DataJpaTestBase {

    // CyclePositionPersistenceAdapter가 요구하는 StrategyLookupPort — 슬라이스 테스트라 실제 구현체
    // (com.kista.strategyconfig..., package-private)를 가져올 수 없어 이미 @Import된 legacy
    // StrategyPersistenceAdapter를 감싸는 최소 구현을 둔다(테스트에서 실제 호출되는 2개 메서드만 구현)
    @TestConfiguration
    static class StrategyLookupTestConfig {
        @Bean
        StrategyLookupPort strategyLookupPort(StrategyPersistenceAdapter strategyPort) {
            return new StrategyLookupPort() {
                @Override
                public List<StrategyRef> findAllActive() {
                    throw new UnsupportedOperationException("테스트에서 미사용");
                }

                @Override
                public List<StrategyRef> findByAccountId(UUID accountId) {
                    throw new UnsupportedOperationException("테스트에서 미사용");
                }

                @Override
                public Optional<StrategyRef> findById(UUID id) {
                    throw new UnsupportedOperationException("테스트에서 미사용");
                }

                @Override
                public StrategyTicker findTickerById(UUID id) {
                    return strategyPort.findById(id).map(Strategy::ticker).orElse(null);
                }

                @Override
                public Map<UUID, StrategyTicker> findTickersByIds(Collection<UUID> ids) {
                    return strategyPort.findTickersByIds(ids);
                }
            };
        }
    }

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
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
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
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
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
    void findFirstOne_returnsOpeningPositionForCycle() {
        Instant openingAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(2, ChronoUnit.HOURS);
        UUID cycleId = insertCycleChain(accountId, false, false);
        UUID openingId = insertPosition(cycleId, new BigDecimal("1000.00"), openingAt, false);
        insertPosition(cycleId, new BigDecimal("900.00"), openingAt.plus(1, ChronoUnit.HOURS), false);

        Optional<CyclePosition> result = cyclePositionAdapter.findFirstOne(cycleId);

        assertThat(result).containsInstanceOf(CyclePosition.class);
        assertThat(result).get().extracting(CyclePosition::id).isEqualTo(openingId);
    }

    @Test
    void findFirstOne_returnsEmptyWhenCycleHasNoPositions() {
        UUID cycleId = insertCycleChain(accountId, false, false);

        Optional<CyclePosition> result = cyclePositionAdapter.findFirstOne(cycleId);

        assertThat(result).isEmpty();
    }

    // ===== findFirstByCycleIds / findLatestByCycleIds — 목록 조회 배치 조회 =====

    @Test
    void findFirstByCycleIds_returnsOpeningPositionPerCycleExcludingDeleted() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(2, ChronoUnit.HOURS);
        UUID cycleA = insertCycleChain(accountId, false, false);
        UUID cycleB = insertCycleChain(accountId, false, false);
        UUID otherCycle = insertCycleChain(accountId, false, false); // 조회 대상 아님

        UUID openingA = insertPosition(cycleA, new BigDecimal("1.00"), base, false);
        insertPosition(cycleA, new BigDecimal("2.00"), base.plus(1, ChronoUnit.HOURS), false); // A의 최신 — 개장 아님
        UUID deletedOpeningB = insertPosition(cycleB, new BigDecimal("3.00"), base, true); // 삭제된 개장 — 제외
        UUID firstActiveB = insertPosition(cycleB, new BigDecimal("4.00"), base.plus(1, ChronoUnit.HOURS), false);
        insertPosition(otherCycle, new BigDecimal("5.00"), base, false);

        Map<UUID, CyclePosition> result = cyclePositionAdapter.findFirstByCycleIds(List.of(cycleA, cycleB));

        assertThat(result).hasSize(2);
        assertThat(result.get(cycleA).id()).isEqualTo(openingA);
        assertThat(result.get(cycleB).id()).isEqualTo(firstActiveB);
        assertThat(result.get(cycleB).id()).isNotEqualTo(deletedOpeningB);
        assertThat(result).doesNotContainKey(otherCycle);
    }

    @Test
    void findFirstByCycleIds_emptyCollection_returnsEmptyMap() {
        assertThat(cyclePositionAdapter.findFirstByCycleIds(List.of())).isEmpty();
    }

    @Test
    void findLatestByCycleIds_returnsMostRecentPositionPerCycleExcludingDeleted() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(2, ChronoUnit.HOURS);
        UUID cycleA = insertCycleChain(accountId, false, false);
        UUID cycleB = insertCycleChain(accountId, false, false);

        insertPosition(cycleA, new BigDecimal("1.00"), base, false);
        UUID latestA = insertPosition(cycleA, new BigDecimal("2.00"), base.plus(1, ChronoUnit.HOURS), false);
        UUID firstB = insertPosition(cycleB, new BigDecimal("3.00"), base, false);
        insertPosition(cycleB, new BigDecimal("4.00"), base.plus(1, ChronoUnit.HOURS), true); // 삭제된 최신 — 제외

        Map<UUID, CyclePosition> result = cyclePositionAdapter.findLatestByCycleIds(List.of(cycleA, cycleB));

        assertThat(result).hasSize(2);
        assertThat(result.get(cycleA).id()).isEqualTo(latestA);
        assertThat(result.get(cycleB).id()).isEqualTo(firstB);
    }

    @Test
    void findLatestByCycleIds_emptyCollection_returnsEmptyMap() {
        assertThat(cyclePositionAdapter.findLatestByCycleIds(List.of())).isEmpty();
    }

    @Test
    void findFirstOne_skipsSoftDeletedOpeningPosition() {
        Instant openingAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(2, ChronoUnit.HOURS);
        UUID cycleId = insertCycleChain(accountId, false, false);
        insertPosition(cycleId, new BigDecimal("1000.00"), openingAt, true);
        UUID firstActiveId = insertPosition(
                cycleId, new BigDecimal("900.00"), openingAt.plus(1, ChronoUnit.HOURS), false);

        Optional<CyclePosition> result = cyclePositionAdapter.findFirstOne(cycleId);

        assertThat(result).get().extracting(CyclePosition::id).isEqualTo(firstActiveId);
    }

    @Test
    void findByCyclePositionIds_returnsDetailsKeyedByPositionId() {
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
        ));
        StrategyVersion version = strategyVersionAdapter.save(new StrategyVersion(null, strategy.id(), 1, null, null));
        StrategyCycle cycle = strategyCycleAdapter.save(new StrategyCycle(
                null, strategy.id(), version.id(), new BigDecimal("1000.00"),
                null, LocalDate.now(), null, null, null
        ));
        CyclePosition posA = cyclePositionAdapter.save(CyclePosition.initialSnapshot(cycle.id(), new BigDecimal("1000.00")));
        CyclePosition posB = cyclePositionAdapter.save(new CyclePosition(
                null, cycle.id(), new BigDecimal("900.00"),
                new BigDecimal("25.00"), new BigDecimal("24.00"), 5, null, null
        ));
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(posA.id(), false));
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(posB.id(), true));

        var result = cyclePositionInfiniteDetailAdapter.findByCyclePositionIds(
                List.of(posA.id(), posB.id(), UUID.randomUUID()));

        assertThat(result).hasSize(2);
        assertThat(result.get(posA.id()).isReverseMode()).isFalse();
        assertThat(result.get(posB.id()).isReverseMode()).isTrue();
    }

    @Test
    void findByCyclePositionIds_emptyCollection_returnsEmptyMap() {
        assertThat(cyclePositionInfiniteDetailAdapter.findByCyclePositionIds(List.of())).isEmpty();
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

        String migration = Files.readString(Path.of("src/main/resources/db/migration/V1__init.sql"));

        assertThat(migration).contains("CREATE TABLE cycle_position_infinite");
        assertThat(migration).contains("is_reverse_mode   BOOLEAN     NOT NULL");
        assertThat(migration).contains("deleted_at        TIMESTAMPTZ");
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

    private UUID insertPosition(UUID cycleId, BigDecimal usdDeposit, Instant createdAt, boolean deleted) {
        UUID positionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO cycle_position (id, strategy_cycle_id, usd_deposit, holdings, created_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                positionId, cycleId, usdDeposit, 0,
                Timestamp.from(createdAt), deleted ? Timestamp.from(createdAt) : null);
        return positionId;
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

    // ===== findByCycleIdsAndRange — equity curve type 필터가 DB 조회 자체를 cycleIds로 좁히는지 검증 =====

    @Test
    void findByCycleIdsAndRange_returnsEmpty_whenCycleIdsIsEmpty() {
        List<CyclePosition> result = cyclePositionAdapter.findByCycleIdsAndRange(
                java.util.Set.of(), Instant.EPOCH, Instant.now());

        assertThat(result).isEmpty();
    }

    @Test
    void findByCycleIdsAndRange_onlyIncludesRequestedCycleIds() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(1, ChronoUnit.HOURS);
        UUID includedCycleId = insertCycleChain(accountId, false, false);
        insertPosition(includedCycleId, new BigDecimal("111.00"), base, false);

        UUID excludedCycleId = insertCycleChain(accountId, false, false);
        insertPosition(excludedCycleId, new BigDecimal("999.00"), base, false);

        List<CyclePosition> result = cyclePositionAdapter.findByCycleIdsAndRange(
                java.util.Set.of(includedCycleId), base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.HOURS));

        assertThat(result).extracting(CyclePosition::usdDeposit)
                .containsExactly(new BigDecimal("111.00"));
    }

    @Test
    void findByCycleIdsAndRange_excludesSoftDeletedAtCycleLevel() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(1, ChronoUnit.HOURS);

        UUID activeCycleId = insertCycleChain(accountId, false, false);
        insertPosition(activeCycleId, new BigDecimal("1.00"), base, false); // 유일하게 살아있는 행

        insertPosition(activeCycleId, new BigDecimal("2.00"), base, true); // cp 자체 삭제

        UUID deletedCycleId = insertCycleChain(accountId, false, true); // sc 삭제
        insertPosition(deletedCycleId, new BigDecimal("3.00"), base, false);

        List<CyclePosition> result = cyclePositionAdapter.findByCycleIdsAndRange(
                java.util.Set.of(activeCycleId, deletedCycleId),
                base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.HOURS));

        assertThat(result).extracting(CyclePosition::usdDeposit)
                .containsExactly(new BigDecimal("1.00"));
    }

    @Test
    void findByCycleIdsAndRange_ordersAscendingAndRespectsRangeBoundaries() {
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

        List<CyclePosition> result = cyclePositionAdapter.findByCycleIdsAndRange(
                java.util.Set.of(cycleId), from, to);

        assertThat(result).extracting(CyclePosition::usdDeposit)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"));
    }
}
