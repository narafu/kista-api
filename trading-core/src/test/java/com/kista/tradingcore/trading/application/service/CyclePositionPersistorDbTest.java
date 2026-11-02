package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.strategy.CycleOrderStrategies;
import com.kista.tradingcore.matching.domain.strategy.InfiniteCycleOrderStrategy;
import com.kista.tradingcore.support.TradingFixtures;
import com.kista.tradingcore.trading.application.port.output.*;
import com.kista.tradingcore.trading.domain.model.BatchContext;
import com.kista.tradingcore.trading.domain.model.Strategy;
import com.kista.tradingcore.trading.domain.model.StrategyCycle;
import com.kista.shared.kernel.StrategyCycleSeedType;
import com.kista.shared.kernel.StrategyStatus;
import com.kista.shared.kernel.StrategyType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

// 포지션·상세·리포트 마커 원자성 — markReported(JdbcTemplate)가 JPA 트랜잭션에 합류해 함께 롤백되는지 실 DB로 검증.
// 테스트 트랜잭션을 끄고(NOT_SUPPORTED) persistor의 TransactionOperations가 유일한 경계가 되게 한다. 커밋된 행은 계좌 삭제(FK CASCADE)로 정리.
@Execution(ExecutionMode.SAME_THREAD)
@Import(CyclePositionPersistorDbTest.AdaptersConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CyclePositionPersistorDbTest extends DataJpaTestBase {

    // 실제 어댑터 3종(package-private이라 이름 필터로 스캔)
    @org.springframework.boot.test.context.TestConfiguration
    @ComponentScan(basePackages = "com.kista.tradingcore.trading.adapter.out.persistence", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = {
                    ".*\\.CyclePositionPersistenceAdapter", ".*\\.CyclePositionInfiniteDetailPersistenceAdapter",
                    ".*\\.TradingBatchRunPersistenceAdapter", ".*\\.StrategyPersistenceAdapter"}))
    static class AdaptersConfig {
    }

    static final LocalDate TODAY = LocalDate.of(2026, 6, 29);

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager txManager;
    @Autowired CyclePositionPort cyclePositionPort;
    @Autowired CyclePositionInfiniteDetailPort detailPort;
    @Autowired TradingBatchRunPort batchRunPort;

    private UUID accountId;
    private UUID strategyId;
    private UUID cycleId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        strategyId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, UUID.randomUUID(), "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "INFINITE", "SOXL", "ACTIVE", "NONE");
        jdbcTemplate.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) VALUES (?, ?, ?, ?, ?, now())",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), TODAY);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM trading_batch_report WHERE strategy_id = ?", strategyId);
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId); // strategy 이하 FK CASCADE
    }

    @Test
    void saveCyclePosition_rollsBackPositionAndDetail_whenMarkReportedFails() {
        // 실 어댑터의 INSERT 실행 후 예외 — 마커 행이 이미 쓰인 뒤 실패해도 position·detail과 함께 롤백돼야 한다
        TradingBatchRunPort failing = spy(batchRunPort);
        doAnswer(inv -> {
            inv.callRealMethod();
            throw new IllegalStateException("주입된 실패");
        }).when(failing).markReported(any(), any());

        assertThatThrownBy(() -> persistor(failing).saveCyclePosition(TODAY, balance(), ctx(), new BigDecimal("50.00"), null))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("cycle_position WHERE strategy_cycle_id = ?", cycleId)).isZero();
        assertThat(count("trading_batch_report WHERE strategy_id = ?", strategyId)).isZero();
    }

    @Test
    void saveCyclePosition_commitsPositionDetailAndMarker_whenAllSucceed() {
        persistor(batchRunPort).saveCyclePosition(TODAY, balance(), ctx(), new BigDecimal("50.00"), null);

        assertThat(count("cycle_position WHERE strategy_cycle_id = ?", cycleId)).isEqualTo(1);
        assertThat(count("cycle_position_infinite WHERE cycle_position_id IN (SELECT id FROM cycle_position WHERE strategy_cycle_id = ?)", cycleId)).isEqualTo(1);
        assertThat(count("trading_batch_report WHERE strategy_id = ?", strategyId)).isEqualTo(1);
    }

    private CyclePositionPersistor persistor(TradingBatchRunPort batchRun) {
        CycleOrderStrategies strategies = new CycleOrderStrategies(java.util.List.of(new InfiniteCycleOrderStrategy(null, null)));
        return new CyclePositionPersistor(cyclePositionPort, detailPort, mock(StrategyInfiniteDetailPort.class),
                mock(StrategyCyclePort.class), mock(CycleRotationService.class), mock(ApplicationEventPublisher.class),
                strategies, mock(VrCycleRolloverService.class), batchRun, new TransactionTemplate(txManager));
    }

    private AccountBalance balance() {
        return new AccountBalance(10, new BigDecimal("45.00"), new BigDecimal("1000.00")); // 보유 있음 — 사이클 종료·rotation 미발동
    }

    private BatchContext ctx() {
        Strategy strategy = new Strategy(strategyId, accountId, StrategyType.INFINITE,
                StrategyStatus.ACTIVE, StrategyTicker.SOXL, StrategyCycleSeedType.NONE);
        StrategyCycle cycle = new StrategyCycle(cycleId, strategyId, UUID.randomUUID(),
                new BigDecimal("1000.00"), null, TODAY, null, Instant.now(), null);
        return new BatchContext(strategy, cycle, TradingFixtures.tradingAccount(accountId, UUID.randomUUID()),
                TradingFixtures.tradingUserProfile(UUID.randomUUID()));
    }

    private Integer count(String fromWhere, UUID id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class, id);
    }
}
