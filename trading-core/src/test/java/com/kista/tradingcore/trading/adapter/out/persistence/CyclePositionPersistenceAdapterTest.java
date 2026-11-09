package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.domain.model.CyclePosition;
import com.kista.tradingcore.trading.domain.model.CyclePositionHistoryEntry;
import com.kista.tradingcore.trading.domain.model.CyclePositionInfiniteDetail;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Collection;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import java.util.ArrayList;
import java.util.Set;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyVersionPersistenceAdapter.class,
        StrategyCyclePersistenceAdapter.class,
        CyclePositionPersistenceAdapter.class,
        CyclePositionInfiniteDetailPersistenceAdapter.class
})
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class CyclePositionPersistenceAdapterTest extends DataJpaTestBase {

    // CyclePositionPersistenceAdapter가 요구하는 StrategyPort — strategyconfig 모듈 병합 이후 이 테스트와
    // 같은 패키지(com.kista.tradingcore.trading.adapter.out.persistence)로 옮겨온 실제 구현체 StrategyPersistenceAdapter를
    // 그대로 @Import해 StrategyPort 빈으로 사용한다(래퍼 불필요).
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
    }

    @Test
    void updateBalance_updatesOnlyBalanceFieldsInPlace() {
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        StrategyVersion version = strategyVersionAdapter.save(new StrategyVersion(null, strategy.id(), 1, null, null));
        StrategyCycle cycle = strategyCycleAdapter.save(new StrategyCycle(
                null, strategy.id(), version.id(), new BigDecimal("1000.00"), null, LocalDate.now(), null, null, null));
        CyclePosition saved = cyclePositionAdapter.save(new CyclePosition(
                null, cycle.id(), new BigDecimal("900.00"), new BigDecimal("30.00"), BigDecimal.TEN, BigDecimal.valueOf(5), null, null));
        entityManager.flush();

        cyclePositionAdapter.updateBalance(saved.id(), new AccountBalance(
                BigDecimal.valueOf(8), new BigDecimal("12.5000"), new BigDecimal("1200.00")));
        entityManager.flush();
        entityManager.clear();

        CyclePosition reloaded = cyclePositionAdapter.findLatestOne(cycle.id()).orElseThrow();
        assertThat(reloaded.id()).isEqualTo(saved.id());
        assertThat(reloaded.holdings()).isEqualByComparingTo("8");
        assertThat(reloaded.avgPrice()).isEqualByComparingTo("12.5000");
        assertThat(reloaded.usdDeposit()).isEqualByComparingTo("1200.00");
        assertThat(reloaded.closingPrice()).isEqualByComparingTo("30.00");
    }

    @Test
    void save_holdings_numericRoundTrip_normalized() {
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE
        ));
        StrategyVersion version = strategyVersionAdapter.save(new StrategyVersion(null, strategy.id(), 1, null, null));
        StrategyCycle cycle = strategyCycleAdapter.save(new StrategyCycle(
                null, strategy.id(), version.id(), new BigDecimal("1000.00"),
                null, LocalDate.now(), null, null, null
        ));
        CyclePosition fractional = cyclePositionAdapter.save(new CyclePosition(
                null, cycle.id(), new BigDecimal("900.00"), null, new BigDecimal("81300813.0081"),
                new BigDecimal("0.00123"), null, null));
        entityManager.flush();
        entityManager.clear();
        assertThat(cyclePositionAdapter.findLatestOne(cycle.id()).orElseThrow().holdings())
                .isEqualTo(new BigDecimal("0.00123"));

        // 정수 5는 numeric(20,8) 왕복 뒤에도 5.00000000이 아니라 5로 정규화
        CyclePosition integral = cyclePositionAdapter.save(new CyclePosition(
                null, cycle.id(), new BigDecimal("900.00"), null, BigDecimal.TEN, BigDecimal.valueOf(5), null, null));
        entityManager.flush();
        entityManager.clear();
        assertThat(fractional.id()).isNotNull();
        // 이력 entry(계좌 커서 경로): holdings 정규화(5.00000000이 아니라 5) + 전략 ticker·type 채움
        assertThat(cyclePositionAdapter.findByAccountIdWithCursor(accountId,
                Instant.EPOCH, Instant.now().plusSeconds(60), 10))
                .isNotEmpty()
                .extracting(CyclePositionHistoryEntry::holdings)
                .contains(new BigDecimal("5"));
        assertThat(cyclePositionAdapter.findByAccountIdWithCursor(accountId,
                Instant.EPOCH, Instant.now().plusSeconds(60), 10))
                .allSatisfy(e -> {
                    assertThat(e.ticker()).isEqualTo(StrategyTicker.SOXL);
                    assertThat(e.strategyType()).isEqualTo(StrategyType.INFINITE);
                });
        // 저장 순서(생성 시각)에 기대지 않고 저장한 행 id로 직접 확인
        assertThat(cyclePositionAdapter.findLatestByCycleId(cycle.id(), 10))
                .filteredOn(p -> p.id().equals(integral.id()))
                .singleElement()
                .satisfies(p -> assertThat(p.holdings()).isEqualTo(new BigDecimal("5")));
    }

    @Test
    void findLatestByCycleId_returnsMostRecentDetailsFirst() {
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
                new BigDecimal("25.00"), new BigDecimal("24.00"), BigDecimal.valueOf(5), null, null
        ));
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(newer.id(), true));

        List<CyclePositionInfiniteDetail> latest = cyclePositionInfiniteDetailAdapter.findLatestByCycleId(cycle.id(), 2);

        assertThat(latest)
                .extracting(CyclePositionInfiniteDetail::cyclePositionId)
                .containsExactly(newer.id(), older.id());
        assertThat(latest)
                .extracting(CyclePositionInfiniteDetail::isReverseMode)
                .containsExactly(true, false);
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

    // ===== findLatestByCycleId / findLatestByStrategyId — limit 만큼 최신순 반환 (별지점 5거래일 윈도우) =====

    @Test
    void findLatestByCycleId_returnsUpToLimitNewestFirstExcludingDeleted() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(10, ChronoUnit.HOURS);
        UUID cycleId = insertCycleChain(accountId, false, false);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ids.add(insertPosition(cycleId, new BigDecimal(i + 1), base.plus(i, ChronoUnit.HOURS), false));
        }
        insertPosition(cycleId, new BigDecimal("99.00"), base.plus(7, ChronoUnit.HOURS), true); // 삭제된 최신 — 제외

        assertThat(cyclePositionAdapter.findLatestByCycleId(cycleId, 5))
                .extracting(CyclePosition::id)
                .containsExactly(ids.get(5), ids.get(4), ids.get(3), ids.get(2), ids.get(1));
    }

    @Test
    void findLatestWithInfiniteDetailByCycleId_excludesPositionsWithoutDetail() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(10, ChronoUnit.HOURS);
        UUID cycleId = insertCycleChain(accountId, false, false);
        List<UUID> detailed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            UUID id = insertPosition(cycleId, new BigDecimal(i + 1), base.plus(i, ChronoUnit.HOURS), false);
            cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(id, false));
            detailed.add(id);
        }
        // 최신 2건은 수동 보정형(상세 없음) — 창에서 제외돼야 한다
        insertPosition(cycleId, new BigDecimal("50.00"), base.plus(4, ChronoUnit.HOURS), false);
        insertPosition(cycleId, new BigDecimal("60.00"), base.plus(5, ChronoUnit.HOURS), false);

        assertThat(cyclePositionAdapter.findLatestWithInfiniteDetailByCycleId(cycleId, 5))
                .extracting(CyclePosition::id)
                .containsExactly(detailed.get(2), detailed.get(1), detailed.get(0));
    }

    @Test
    void findLatestByStrategyId_returnsUpToLimitNewestFirst() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(10, ChronoUnit.HOURS);
        UUID cycleId = insertCycleChain(accountId, false, false);
        UUID strategyId = jdbcTemplate.queryForObject(
                "SELECT strategy_id FROM strategy_cycle WHERE id = ?", UUID.class, cycleId);
        insertPosition(cycleId, new BigDecimal("1.00"), base, false);
        UUID middle = insertPosition(cycleId, new BigDecimal("2.00"), base.plus(1, ChronoUnit.HOURS), false);
        UUID newest = insertPosition(cycleId, new BigDecimal("3.00"), base.plus(2, ChronoUnit.HOURS), false);

        assertThat(cyclePositionAdapter.findLatestByStrategyId(strategyId, 2))
                .extracting(CyclePosition::id)
                .containsExactly(newest, middle);
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
    void findLatestByCycleIds_skipsPositionsWithoutDetail_andKeysByCycle() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(3, ChronoUnit.HOURS);
        // 사이클 1: 오래된 상세(true) 뒤에 상세 없는 수동 보정형 포지션이 최신
        UUID cycle1 = insertCycleChain(accountId, false, false);
        UUID detailed = insertPosition(cycle1, new BigDecimal("1000.00"), base, false);
        insertPosition(cycle1, new BigDecimal("900.00"), base.plus(1, ChronoUnit.HOURS), false);
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(detailed, true));
        // 사이클 2: 상세 2건 — 최신(false)이 선택돼야 한다
        UUID cycle2 = insertCycleChain(accountId, false, false);
        UUID older = insertPosition(cycle2, new BigDecimal("1000.00"), base, false);
        UUID newer = insertPosition(cycle2, new BigDecimal("900.00"), base.plus(1, ChronoUnit.HOURS), false);
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(older, true));
        cyclePositionInfiniteDetailAdapter.save(new CyclePositionInfiniteDetail(newer, false));

        var result = cyclePositionInfiniteDetailAdapter.findLatestByCycleIds(List.of(cycle1, cycle2, UUID.randomUUID()));

        assertThat(result).hasSize(2);
        assertThat(result.get(cycle1)).isEqualTo(new CyclePositionInfiniteDetail(detailed, true));
        assertThat(result.get(cycle2)).isEqualTo(new CyclePositionInfiniteDetail(newer, false));
    }

    @Test
    void findLatestDetailByCycleIds_emptyCollection_returnsEmptyMap() {
        assertThat(cyclePositionInfiniteDetailAdapter.findLatestByCycleIds(List.of())).isEmpty();
    }

    @Test
    void cyclePositionInfiniteSchema_followAuditConventionAndKeepDeletedHistory() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = 'cycle_position_infinite'
                ORDER BY ordinal_position
                """, String.class))
                .containsExactly("cycle_position_id", "is_reverse_mode", "created_at", "deleted_at");

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name || ':' || data_type || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = 'cycle_position_infinite'
                  AND column_name IN ('is_reverse_mode', 'deleted_at')
                ORDER BY column_name
                """, String.class))
                .containsExactly("deleted_at:timestamp with time zone:YES", "is_reverse_mode:boolean:NO");
    }

    // ===== findByUserAndRange — raw JDBC로 FK 체인·created_at·deleted_at을 직접 통제 =====
    // createdAt은 JPA auditing(@CreatedDate) 대상이라 어댑터 경유로는 값을 지정할 수 없어
    // OrderPersistenceAdapterDbTest와 동일하게 raw INSERT로 우회한다.

    private UUID insertUserAndAccount(boolean deletedAccount) {
        UUID uId = UUID.randomUUID();
        UUID accId = UUID.randomUUID();
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

    // 계좌당 활성 종목 유니크 인덱스 때문에 전략마다 다른 종목을 쓴다
    private final TradingFixtures.TickerSequence tickers = new TradingFixtures.TickerSequence();

    // strategy → strategy_version → strategy_cycle 체인 삽입, cycleId 반환
    private UUID insertCycleChain(UUID accId, boolean deletedStrategy, boolean deletedCycle) {
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), now(), ?)",
                strategyId, accId, "INFINITE", tickers.next().name(), "ACTIVE", "NONE",
                deletedStrategy ? Timestamp.from(Instant.now()) : null);
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, open_amount, start_date, created_at, deleted_at) "
                        + "VALUES (?, ?, ?, ?, 0, ?, now(), ?)",
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
                Set.of(), Instant.EPOCH, Instant.now());

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
                Set.of(includedCycleId), base.minus(1, ChronoUnit.HOURS), base.plus(1, ChronoUnit.HOURS));

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
                Set.of(activeCycleId, deletedCycleId),
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
                Set.of(cycleId), from, to);

        assertThat(result).extracting(CyclePosition::usdDeposit)
                .containsExactly(new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"));
    }
}
