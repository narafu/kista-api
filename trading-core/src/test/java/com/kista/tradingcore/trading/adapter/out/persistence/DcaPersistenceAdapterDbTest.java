package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DCA 설정·회차 이력 실 DB 왕복 — V6 마이그레이션 + ddl-auto validate 검증 포함
@Import({StrategyDcaDetailPersistenceAdapter.class, DcaExecutionPersistenceAdapter.class})
@Execution(ExecutionMode.SAME_THREAD)
class DcaPersistenceAdapterDbTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired StrategyDcaDetailPersistenceAdapter dcaDetail;
    @Autowired DcaExecutionPersistenceAdapter executions;

    UUID strategyId;
    UUID versionId;

    @BeforeEach
    void setUp() {
        UUID accountId = UUID.randomUUID();
        strategyId = UUID.randomUUID();
        versionId = UUID.randomUUID();
        // FK 선행 행: account → strategy → strategy_version
        jdbc.update("INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, UUID.randomUUID(), "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        jdbc.update("INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "DCA", "UPRO", "ACTIVE", "NONE");
        jdbc.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
    }

    @Test
    void saves_and_loads_active_config() {
        DcaConfig config = new DcaConfig(OrderDirection.BUY, new BigDecimal("300.00"), OrderType.MOC,
                DcaFrequency.MONTHLY, null, null);
        dcaDetail.save(versionId, config);
        assertThat(dcaDetail.findActiveByStrategyId(strategyId)).contains(config);
        assertThat(dcaDetail.findActiveByStrategyIds(List.of(strategyId))).containsEntry(strategyId, config);
    }

    @Test
    void weekly_config_roundtrips_day_of_week() {
        DcaConfig config = new DcaConfig(OrderDirection.SELL, new BigDecimal("100.00"), OrderType.MOO,
                DcaFrequency.WEEKLY, DayOfWeek.WEDNESDAY, null);
        dcaDetail.save(versionId, config);
        assertThat(dcaDetail.findActiveByStrategyId(strategyId)).contains(config);
    }

    @Test
    void insert_if_absent_is_idempotent_per_trade_date() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        assertThat(executions.insertIfAbsent(DcaExecution.skipped(strategyId, day, DcaSkipReason.NO_HOLDINGS, null, null))).isTrue();
        assertThat(executions.insertIfAbsent(DcaExecution.skipped(strategyId, day, DcaSkipReason.NO_HOLDINGS, null, null))).isFalse();
        assertThat(executions.find(strategyId, day)).get().extracting(DcaExecution::skipReason).isEqualTo(DcaSkipReason.NO_HOLDINGS);
        assertThat(executions.count(strategyId)).isEqualTo(1);
    }

    @Test
    void page_and_latest_are_trade_date_desc() {
        executions.insertIfAbsent(DcaExecution.skipped(strategyId, LocalDate.of(2026, 10, 1), DcaSkipReason.NO_HOLDINGS, null, null));
        executions.insertIfAbsent(DcaExecution.ordered(strategyId, LocalDate.of(2026, 10, 2), null, 3, new BigDecimal("87.12")));
        assertThat(executions.findPage(strategyId, 0, 10)).extracting(DcaExecution::tradeDate)
                .containsExactly(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1));
        assertThat(executions.findLatestByStrategyIds(List.of(strategyId)).get(strategyId).tradeDate())
                .isEqualTo(LocalDate.of(2026, 10, 2));
    }
}
