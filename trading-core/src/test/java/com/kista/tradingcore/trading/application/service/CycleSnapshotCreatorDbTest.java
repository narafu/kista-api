package com.kista.tradingcore.trading.application.service;

import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.trading.application.port.output.CycleCapitalEventPort;
import com.kista.tradingcore.trading.application.port.output.CyclePositionPort;
import com.kista.tradingcore.trading.application.port.output.StrategyCyclePort;
import com.kista.tradingcore.trading.application.port.output.StrategyCycleVrPort;
import com.kista.tradingcore.trading.application.port.output.StrategyVersionPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

// CQ-45: VR 롤오버의 옛 사이클 종료(markEnded)와 새 사이클 생성이 한 트랜잭션인지 실 DB로 검증.
// 테스트 트랜잭션을 끄고(NOT_SUPPORTED) CycleSnapshotCreator의 @Transactional이 유일한 경계가 되게 한다. 커밋된 행은 계좌 삭제(FK CASCADE)로 정리.
@Execution(ExecutionMode.SAME_THREAD)
@Import({CycleSnapshotCreator.class, CycleSnapshotCreatorDbTest.AdaptersConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CycleSnapshotCreatorDbTest extends DataJpaTestBase {

    // 실제 사이클 어댑터(package-private이라 이름 필터로 스캔)
    @TestConfiguration
    @ComponentScan(basePackages = "com.kista.tradingcore.trading.adapter.out.persistence", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.StrategyCyclePersistenceAdapter"))
    static class AdaptersConfig {
    }

    static final LocalDate TODAY = LocalDate.of(2026, 6, 29);

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CycleSnapshotCreator creator;
    @MockitoSpyBean StrategyCyclePort strategyCyclePort; // 실 어댑터를 감싸 새 사이클 저장만 실패시킨다
    @MockitoBean CyclePositionPort cyclePositionPort;
    @MockitoBean StrategyCycleVrPort strategyCycleVrPort;
    @MockitoBean StrategyVersionPort strategyVersionPort;
    @MockitoBean VrStrategyDetailService vrStrategyDetailService;
    @MockitoBean CycleCapitalEventPort cycleCapitalEventPort; // VR 재설정 이력 포트(이 테스트 대상 아님)

    private UUID accountId;
    private UUID strategyId;
    private UUID versionId;
    private UUID cycleId;

    @BeforeEach
    void setUp() {
        accountId = UUID.randomUUID();
        strategyId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, UUID.randomUUID(), "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "VR", "TQQQ", "ACTIVE", "NONE");
        jdbcTemplate.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, open_amount, start_date, created_at) VALUES (?, ?, ?, ?, 0, ?, now())",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), TODAY.minusWeeks(2));
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM accounts WHERE id = ?", accountId); // strategy 이하 FK CASCADE
    }

    @Test
    @DisplayName("CQ-45: 새 사이클 저장이 실패하면 옛 사이클 markEnded도 롤백된다")
    void rolloverVrCycle_rollsBackMarkEnded_whenNewCycleSaveFails() {
        doThrow(new IllegalStateException("주입된 실패")).when(strategyCyclePort).save(any());

        assertThatThrownBy(() -> creator.rolloverVrCycle(cycleId, new BigDecimal("1100.00"), TODAY, start()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count("strategy_cycle WHERE id = ? AND end_date IS NULL AND end_amount IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("CQ-45: 성공하면 옛 사이클 종료와 새 사이클 생성이 함께 커밋된다")
    void rolloverVrCycle_commitsEndAndNewCycle_whenAllSucceed() {
        creator.rolloverVrCycle(cycleId, new BigDecimal("1100.00"), TODAY, start());

        assertThat(count("strategy_cycle WHERE id = ? AND end_date IS NOT NULL AND end_amount IS NOT NULL")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM strategy_cycle WHERE strategy_id = ?", Integer.class, strategyId)).isEqualTo(2);
    }

    private CycleSnapshotCreator.VrCycleStart start() {
        return new CycleSnapshotCreator.VrCycleStart(strategyId, versionId,
                new AccountBalance(5, new BigDecimal("100.00"), new BigDecimal("1000.00")),
                new BigDecimal("120.00"), new BigDecimal("900.00"), 10, new BigDecimal("0.50"), TODAY);
    }

    private Integer count(String fromWhere) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + fromWhere, Integer.class, cycleId);
    }
}
