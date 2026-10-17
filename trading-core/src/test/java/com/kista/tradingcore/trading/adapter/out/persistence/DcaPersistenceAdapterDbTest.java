package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DCA 설정·조건·회차 이력 실 DB 왕복 — V6·V7 마이그레이션 + ddl-auto validate 검증 포함
@Import({StrategyDcaDetailPersistenceAdapter.class, DcaExecutionPersistenceAdapter.class})
@Execution(ExecutionMode.SAME_THREAD)
class DcaPersistenceAdapterDbTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
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
        executions.insertIfAbsent(DcaExecution.ordered(strategyId, LocalDate.of(2026, 10, 2), null, BigDecimal.valueOf(3), new BigDecimal("87.12")));
        assertThat(executions.findPage(strategyId, 0, 10)).extracting(DcaExecution::tradeDate)
                .containsExactly(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1));
        assertThat(executions.findLatestByStrategyIds(List.of(strategyId)).get(strategyId).tradeDate())
                .isEqualTo(LocalDate.of(2026, 10, 2));
    }

    // DB numeric(10,2)에서 scale 2로 돌아오므로 기대값도 scale 2로 쓴다 (stripTrailingZeros 하지 않음)
    private static DcaCondition rsi(String threshold) {
        return new DcaCondition(DcaIndicator.RSI, DcaOperator.LTE, null, new BigDecimal(threshold));
    }

    private static DcaConfig configWith(List<DcaCondition> conditions) {
        return new DcaConfig(OrderDirection.BUY, new BigDecimal("300.00"), OrderType.MOC,
                DcaFrequency.MONTHLY, null, null, conditions);
    }

    @Test
    void conditions_round_trip_in_input_order() {
        List<DcaCondition> conds = List.of(
                rsi("35.00"),
                new DcaCondition(DcaIndicator.SMA, DcaOperator.LT, 200, null),
                new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5.50")));
        dcaDetail.save(versionId, configWith(conds));
        assertThat(dcaDetail.findActiveByStrategyId(strategyId).orElseThrow().conditions()).containsExactlyElementsOf(conds);
        assertThat(dcaDetail.findActiveByStrategyIds(List.of(strategyId)).get(strategyId).conditions()).containsExactlyElementsOf(conds);
    }

    @Test
    void resave_replaces_conditions() {
        // 같은 version 재저장 — 조건 2개 → 1개(순서 0 재사용)로 교체되어도 UNIQUE 충돌 없음
        dcaDetail.save(versionId, configWith(List.of(rsi("30.00"), rsi("40.00"))));
        dcaDetail.save(versionId, configWith(List.of(rsi("50.00"))));
        assertThat(dcaDetail.findActiveByStrategyId(strategyId).orElseThrow().conditions()).containsExactly(rsi("50.00"));
    }

    @Test
    void save_returns_threshold_normalized_to_scale_2() {
        // 입력 scale(0)이 달라도 save() 반환값은 조회와 같은 scale 2
        DcaConfig saved = dcaDetail.save(versionId, configWith(List.of(rsi("35"))));
        assertThat(saved.conditions().getFirst().threshold()).isEqualByComparingTo("35").hasScaleOf(2);
        em.flush();
        em.clear(); // 1차 캐시가 아닌 DB 재조회 값과 비교
        assertThat(dcaDetail.findActiveByStrategyIds(List.of(strategyId)).get(strategyId)).isEqualTo(saved);
    }

    @Test
    void find_by_empty_keys_returns_empty_map() {
        assertThat(dcaDetail.findActiveByStrategyIds(List.of())).isEmpty();
        // 활성 버전이 없는 전략 키도 빈 결과
        assertThat(dcaDetail.findActiveByStrategyIds(List.of(UUID.randomUUID()))).isEmpty();
    }

    @Test
    void config_without_conditions_loads_empty_list() {
        dcaDetail.save(versionId, configWith(List.of()));
        assertThat(dcaDetail.findActiveByStrategyId(strategyId).orElseThrow().conditions()).isEmpty();
    }
}
