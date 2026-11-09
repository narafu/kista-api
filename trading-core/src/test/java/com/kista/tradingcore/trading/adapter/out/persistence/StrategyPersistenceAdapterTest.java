package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyInfiniteDetail;
import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import com.kista.shared.support.DataJpaTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.kista.tradingcore.trading.domain.model.Order;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import com.kista.shared.kernel.StrategyType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyCycleSeedType;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyInfiniteDetailPersistenceAdapter.class,
        StrategyVersionPersistenceAdapter.class
})
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
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
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
    }

    @Test
    void findByAccountIdAndTicker_returnsOnlyMatchingTickerOfAccount() {
        Strategy upro = saveActiveStrategy(accountId, StrategyTicker.UPRO);
        saveActiveStrategy(accountId, StrategyTicker.SOXL);
        saveActiveStrategy(insertAccountOfUser(Boolean.TRUE), StrategyTicker.UPRO);

        assertThat(strategyAdapter.findByAccountIdAndTicker(accountId, StrategyTicker.UPRO))
                .extracting(Strategy::id).containsExactly(upro.id());
        assertThat(strategyAdapter.findByAccountIdAndTicker(accountId, StrategyTicker.QLD)).isEmpty();
    }

    @Test
    void findByAccountIds_includes_archived() {
        // 통계 경로(findByAccountId(s))는 보관 전략을 포함해야 한다 — 목록 제외는 서비스 계층 필터 몫
        Strategy archived = strategyAdapter.save(new Strategy(null, accountId, StrategyType.INFINITE,
                StrategyStatus.ARCHIVED, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));

        assertThat(strategyAdapter.findByAccountIds(List.of(accountId)).get(accountId))
                .extracting(Strategy::id).containsExactly(archived.id());
        assertThat(strategyAdapter.findByAccountId(accountId)).extracting(Strategy::id).containsExactly(archived.id());
    }

    @Test
    void findAllActive_usesNotifyProfileActiveFlag_notUsersTable() {
        // 활성 프로필 → 포함 (trading 스키마엔 users 테이블이 없어 users 비의존은 스키마가 보장한다)
        Strategy included = saveActiveStrategy(insertAccountOfUser(Boolean.TRUE), StrategyTicker.SOXL);
        // 프로필 inactive → 제외
        saveActiveStrategy(insertAccountOfUser(Boolean.FALSE), StrategyTicker.SOXL);
        // 프로필 행 자체가 없음 → 제외
        saveActiveStrategy(insertAccountOfUser(null), StrategyTicker.SOXL);
        // 활성 프로필이지만 계좌 소프트 삭제 → 제외
        UUID deletedAccountId = insertAccountOfUser(Boolean.TRUE);
        saveActiveStrategy(deletedAccountId, StrategyTicker.SOXL);
        jdbcTemplate.update("UPDATE accounts SET deleted_at = now() WHERE id = ?", deletedAccountId);
        // 활성 프로필이지만 PAUSED 전략 → 제외
        strategyAdapter.save(new Strategy(null, insertAccountOfUser(Boolean.TRUE), StrategyType.INFINITE,
                StrategyStatus.PAUSED, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        entityManager.flush();
        entityManager.clear();

        assertThat(strategyAdapter.findAllActive()).extracting(Strategy::id).containsExactly(included.id());
    }

    // 계좌 삽입 후 accountId 반환. profileActive=null이면 프로필 행을 만들지 않는다
    private UUID insertAccountOfUser(Boolean profileActive) {
        UUID uid = UUID.randomUUID();
        UUID aid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                aid, uid, "계좌", "KIS", "74420614" + aid, "01", "key", "secret");
        if (profileActive != null) {
            jdbcTemplate.update(
                    "INSERT INTO trading.user_notify_profile (user_id, notification_prefs, is_active, updated_at) VALUES (?, '{}', ?, now())",
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
                List.of(versionA.id(), versionB.id(), UUID.randomUUID()));

        assertThat(result).hasSize(2);
        assertThat(result.get(versionA.id()).divisionCount()).isEqualTo(20);
        assertThat(result.get(versionB.id()).divisionCount()).isEqualTo(30);
    }

    @Test
    void findByStrategyVersionIds_emptyCollection_returnsEmptyMap() {
        assertThat(strategyInfiniteDetailAdapter.findByStrategyVersionIds(List.of())).isEmpty();
    }

    @Test
    void strategyInfiniteSchema_followAuditConventionAndKeepDeletedHistory() throws Exception {
        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = 'strategy_infinite_version'
                ORDER BY ordinal_position
                """, String.class))
                .containsExactly("strategy_version_id", "division_count", "created_at", "updated_at", "deleted_at");

        assertThat(jdbcTemplate.queryForList("""
                SELECT column_name || ':' || data_type || ':' || is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'trading' AND table_name = 'strategy_infinite_version'
                  AND column_name IN ('division_count', 'deleted_at')
                ORDER BY column_name
                """, String.class))
                .containsExactly("deleted_at:timestamp with time zone:YES", "division_count:integer:NO");
    }

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 11, 3);

    // 정지 리포트 조회용 시나리오 — 계좌·프로필·전략·버전·사이클·주문 1건을 만들고 전략 id 반환
    private UUID seedStrategyWithOrder(String strategyStatus, LocalDate orderDate, String externalOrderId, String leg) {
        UUID acct = insertAccountOfUser(Boolean.TRUE);
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, 'INFINITE', 'SOXL', ?, 'NONE', now(), now())",
                strategyId, acct, strategyStatus);
        jdbcTemplate.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, 1, now())", versionId, strategyId);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, open_amount, start_date, created_at) VALUES (?, ?, ?, ?, 0, ?, now())",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), TRADE_DATE.minusDays(5));
        jdbcTemplate.update(
                "INSERT INTO orders (account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, order_leg, price, quantity, status, external_order_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SOXL', 'LOC', 'AT_CLOSE', 'BUY', ?, 22.00, 5, 'CANCELLED', ?, now(), now())",
                acct, cycleId, orderDate, leg, externalOrderId);
        return strategyId;
    }

    private List<UUID> notActiveIds() {
        entityManager.flush();
        entityManager.clear();
        return strategyAdapter.findNotActiveWithReportableOrders(TRADE_DATE).stream().map(Strategy::id).toList();
    }

    @Test
    void findNotActiveWithReportableOrders_includesPausedWithTodaysSubmittedOrder() {
        UUID paused = seedStrategyWithOrder("PAUSED", TRADE_DATE, "ORD-1", "BUY_LOC");

        assertThat(notActiveIds()).containsExactly(paused);
    }

    @Test
    void findNotActiveWithReportableOrders_excludesActiveOtherDateUnknownLegAndNoExternalId() {
        seedStrategyWithOrder("ACTIVE", TRADE_DATE, "ORD-1", "BUY_LOC");
        seedStrategyWithOrder("PAUSED", TRADE_DATE.minusDays(1), "ORD-2", "BUY_LOC");
        seedStrategyWithOrder("PAUSED", TRADE_DATE, "ORD-3", Order.UNKNOWN_LEG);
        seedStrategyWithOrder("PAUSED", TRADE_DATE, null, "BUY_LOC");

        assertThat(notActiveIds()).isEmpty();
    }

    @Test
    void findNotActiveWithReportableOrders_excludesSoftDeletedStrategyAccountAndCycle() {
        UUID deletedStrategy = seedStrategyWithOrder("PAUSED", TRADE_DATE, "ORD-1", "BUY_LOC");
        jdbcTemplate.update("UPDATE strategy SET deleted_at = now() WHERE id = ?", deletedStrategy);
        UUID deletedAccount = seedStrategyWithOrder("PAUSED", TRADE_DATE, "ORD-2", "BUY_LOC");
        jdbcTemplate.update("UPDATE accounts SET deleted_at = now() WHERE id = (SELECT account_id FROM strategy WHERE id = ?)", deletedAccount);
        UUID deletedCycle = seedStrategyWithOrder("PAUSED", TRADE_DATE, "ORD-3", "BUY_LOC");
        jdbcTemplate.update("UPDATE strategy_cycle SET deleted_at = now() WHERE strategy_id = ?", deletedCycle);

        assertThat(notActiveIds()).isEmpty();
    }

    @Test
    void findNotActiveWithReportableOrders_excludesEndedCycle() {
        UUID ended = seedStrategyWithOrder("PAUSED", TRADE_DATE, "ORD-1", "BUY_LOC");
        jdbcTemplate.update("UPDATE strategy_cycle SET end_date = ? WHERE strategy_id = ?", TRADE_DATE, ended);

        assertThat(notActiveIds()).isEmpty();
    }

    @Test
    void updateCycleSeedTypeUnlessArchived_changesOnlySeedTypeAndKeepsArchived() {
        Strategy archived = strategyAdapter.save(new Strategy(null, accountId, StrategyType.INFINITE,
                StrategyStatus.ARCHIVED, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        Strategy paused = strategyAdapter.save(new Strategy(null, accountId, StrategyType.INFINITE,
                StrategyStatus.PAUSED, StrategyTicker.UPRO, StrategyCycleSeedType.NONE));
        entityManager.flush();

        assertThat(strategyAdapter.updateCycleSeedTypeUnlessArchived(archived.id(), StrategyCycleSeedType.MAINTAIN)).isFalse();
        assertThat(strategyAdapter.updateCycleSeedTypeUnlessArchived(paused.id(), StrategyCycleSeedType.MAINTAIN)).isTrue();
        entityManager.clear();

        // ARCHIVED 행은 정책·상태 모두 불변, 그 외 행은 정책만 바뀌고 status는 그대로
        Strategy archivedAfter = strategyAdapter.findByIdOrThrow(archived.id());
        assertThat(archivedAfter.status()).isEqualTo(StrategyStatus.ARCHIVED);
        assertThat(archivedAfter.cycleSeedType()).isEqualTo(StrategyCycleSeedType.NONE);
        Strategy pausedAfter = strategyAdapter.findByIdOrThrow(paused.id());
        assertThat(pausedAfter.status()).isEqualTo(StrategyStatus.PAUSED);
        assertThat(pausedAfter.cycleSeedType()).isEqualTo(StrategyCycleSeedType.MAINTAIN);
    }

    @Test
    void updateStatusUnlessArchived_keepsArchivedAndUpdatesOthers() {
        Strategy archived = strategyAdapter.save(new Strategy(null, accountId, StrategyType.INFINITE,
                StrategyStatus.ARCHIVED, StrategyTicker.SOXL, StrategyCycleSeedType.NONE));
        Strategy paused = strategyAdapter.save(new Strategy(null, accountId, StrategyType.INFINITE,
                StrategyStatus.PAUSED, StrategyTicker.UPRO, StrategyCycleSeedType.NONE));
        entityManager.flush();

        assertThat(strategyAdapter.updateStatusUnlessArchived(archived.id(), StrategyStatus.ACTIVE)).isFalse();
        assertThat(strategyAdapter.updateStatusUnlessArchived(paused.id(), StrategyStatus.ACTIVE)).isTrue();
        entityManager.clear();

        assertThat(strategyAdapter.findByIdOrThrow(archived.id()).status()).isEqualTo(StrategyStatus.ARCHIVED);
        assertThat(strategyAdapter.findByIdOrThrow(paused.id()).status()).isEqualTo(StrategyStatus.ACTIVE);
    }

    @Test
    void findNotActiveWithReportableOrders_includesArchivedWithTodaysSubmittedOrder() {
        UUID archived = seedStrategyWithOrder("ARCHIVED", TRADE_DATE, "ORD-1", "BUY_LOC");

        assertThat(notActiveIds()).containsExactly(archived);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 스케쥴러 경로처럼 바깥 트랜잭션 없이 호출 — 어댑터가 자기 트랜잭션을 열어야 한다
    void conditionalStatusWrites_workWithoutOuterTransaction() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, 'INFINITE', 'SOXL', 'ACTIVE', 'NONE', now(), now())",
                id, accountId);
        try {
            assertThat(strategyAdapter.updateStatusUnlessArchived(id, StrategyStatus.PAUSED)).isTrue();
            assertThat(strategyAdapter.archiveIfPaused(id)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM strategy WHERE id = ?", String.class, id)).isEqualTo("ARCHIVED");
        } finally {
            jdbcTemplate.update("DELETE FROM strategy WHERE id = ?", id);
            jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId);
        }
    }

    @Test
    void archiveIfPaused_skipsWhenNotPaused() {
        Strategy active = saveActiveStrategy(accountId, StrategyTicker.UPRO);
        entityManager.flush();

        assertThat(strategyAdapter.archiveIfPaused(active.id())).isFalse();
        entityManager.clear();

        assertThat(strategyAdapter.findByIdOrThrow(active.id()).status()).isEqualTo(StrategyStatus.ACTIVE);
    }
}
