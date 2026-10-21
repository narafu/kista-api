package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;
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

// DCA 설정·조건·회차 이력 실 DB 왕복 — V12 규칙 테이블 마이그레이션 + ddl-auto validate 검증 포함
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

    static final String K1 = "11111111-1111-1111-1111-111111111111";
    static final String K2 = "22222222-2222-2222-2222-222222222222";

    // 저장 왕복 비교용 규칙 — amount·percent는 DB numeric scale 2와 같은 형태로 쓴다
    private static DcaRule rule(String key, OrderDirection direction, DcaFrequency frequency, DayOfWeek dow,
                                OrderType orderType, Integer hour, DcaSizing sizing, List<DcaCondition> conditions) {
        return new DcaRule(key, direction, frequency, dow, null, orderType, hour, sizing, DcaShortfallPolicy.PARTIAL, conditions);
    }

    private static DcaSizing amount(String v) { return new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(v), null); }

    private static DcaRule monthlyBuy(String key, List<DcaCondition> conditions) {
        return rule(key, OrderDirection.BUY, DcaFrequency.MONTHLY, null, OrderType.MOC, null, amount("300.00"), conditions);
    }

    private static DcaStrategyConfig configWith(List<DcaCondition> conditions) {
        return new DcaStrategyConfig(List.of(monthlyBuy(K1, conditions)));
    }

    @Test
    void saves_and_loads_multiple_rules_in_input_order() {
        DcaRule sellPercent = new DcaRule(K2, OrderDirection.SELL, DcaFrequency.WEEKLY, DayOfWeek.WEDNESDAY, null,
                OrderType.MOO, null, new DcaSizing(DcaSizingType.HOLDING_PERCENT, null, new BigDecimal("12.50")),
                DcaShortfallPolicy.REJECT, List.of(rsi("70.00")));
        DcaStrategyConfig config = new DcaStrategyConfig(List.of(monthlyBuy(K1, List.of(rsi("30.00"))), sellPercent));
        dcaDetail.save(versionId, config);
        em.flush();
        em.clear();
        assertThat(dcaDetail.findActiveByStrategyId(strategyId)).contains(config);
        assertThat(dcaDetail.findActiveByStrategyIds(List.of(strategyId))).containsEntry(strategyId, config);
    }

    @Test
    void active_config_follows_latest_version() {
        dcaDetail.save(versionId, configWith(List.of()));
        // 새 버전 생성(기존 버전 soft delete) 후 저장 — 활성 버전 설정만 조회된다
        UUID v2 = UUID.randomUUID();
        jdbc.update("UPDATE strategy_version SET deleted_at = now() WHERE id = ?", versionId);
        jdbc.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())", v2, strategyId, 2);
        DcaStrategyConfig next = new DcaStrategyConfig(List.of(monthlyBuy(K1, List.of()), monthlyBuy(K2, List.of())));
        dcaDetail.save(v2, next);
        assertThat(dcaDetail.findActiveByStrategyId(strategyId)).contains(next);
        assertThat(dcaDetail.findActiveByStrategyIds(List.of(strategyId))).containsEntry(strategyId, next);
    }

    @Test
    void 같은_규칙_같은날_두번째는_insertIfAbsent_false() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        assertThat(executions.insertIfAbsent(DcaExecution.skipped(strategyId, K1, com.kista.shared.kernel.OrderDirection.BUY, day, DcaSkipReason.NO_HOLDINGS, null, null))).isTrue();
        assertThat(executions.insertIfAbsent(DcaExecution.skipped(strategyId, K1, com.kista.shared.kernel.OrderDirection.BUY, day, DcaSkipReason.NO_HOLDINGS, null, null))).isFalse();
        assertThat(executions.find(strategyId, K1, day)).get().extracting(DcaExecution::skipReason).isEqualTo(DcaSkipReason.NO_HOLDINGS);
        assertThat(executions.count(strategyId)).isEqualTo(1);
    }

    @Test
    void 같은_전략_같은날_다른_규칙은_각각_기록() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        assertThat(executions.insertIfAbsent(DcaExecution.skipped(strategyId, K1, com.kista.shared.kernel.OrderDirection.BUY, day, DcaSkipReason.NO_HOLDINGS, null, null))).isTrue();
        assertThat(executions.insertIfAbsent(DcaExecution.ordered(strategyId, K2, com.kista.shared.kernel.OrderDirection.BUY, day, null, BigDecimal.ONE, new BigDecimal("10.00")))).isTrue();
        assertThat(executions.count(strategyId)).isEqualTo(2);
        assertThat(executions.find(strategyId, K2, day)).get().extracting(DcaExecution::ruleKey).isEqualTo(K2);
        assertThat(executions.find(strategyId, K2, LocalDate.of(2026, 10, 15))).isEmpty();
    }

    @Test
    void direction_round_trips_and_same_day_rules_page_without_overlap() {
        LocalDate day = LocalDate.of(2026, 10, 14);
        executions.insertIfAbsent(DcaExecution.skipped(strategyId, K1, OrderDirection.SELL, day, DcaSkipReason.NO_HOLDINGS, null, null));
        executions.insertIfAbsent(DcaExecution.skipped(strategyId, K2, OrderDirection.BUY, day, DcaSkipReason.NO_HOLDINGS, null, null));
        assertThat(executions.find(strategyId, K1, day)).get().extracting(DcaExecution::direction).isEqualTo(OrderDirection.SELL);
        // 같은 거래일 두 행 — 크기 1 페이지 두 장이 겹치지 않고 둘 다 덮는다(id 2차 정렬)
        var first = executions.findPage(strategyId, 0, 1);
        var second = executions.findPage(strategyId, 1, 1);
        assertThat(List.of(first.getFirst().ruleKey(), second.getFirst().ruleKey())).containsExactlyInAnyOrder(K1, K2);
    }

    @Test
    void page_and_latest_per_rule_are_trade_date_desc() {
        executions.insertIfAbsent(DcaExecution.skipped(strategyId, K1, com.kista.shared.kernel.OrderDirection.BUY, LocalDate.of(2026, 10, 1), DcaSkipReason.NO_HOLDINGS, null, null));
        executions.insertIfAbsent(DcaExecution.ordered(strategyId, K1, com.kista.shared.kernel.OrderDirection.BUY, LocalDate.of(2026, 10, 2), null, BigDecimal.valueOf(3), new BigDecimal("87.12")));
        executions.insertIfAbsent(DcaExecution.skipped(strategyId, K2, com.kista.shared.kernel.OrderDirection.BUY, LocalDate.of(2026, 9, 30), DcaSkipReason.NO_HOLDINGS, null, null));
        assertThat(executions.findPage(strategyId, 0, 10)).extracting(DcaExecution::tradeDate)
                .containsExactly(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 30));
        var latest = executions.findLatestByStrategyIds(List.of(strategyId)).get(strategyId);
        assertThat(latest.get(K1).tradeDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(latest.get(K2).tradeDate()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    // DB numeric(10,2)에서 scale 2로 돌아오므로 기대값도 scale 2로 쓴다 (stripTrailingZeros 하지 않음)
    private static DcaCondition rsi(String threshold) {
        return new DcaCondition(DcaIndicator.RSI, DcaOperator.LTE, null, new BigDecimal(threshold));
    }

    @Test
    void conditions_round_trip_in_input_order() {
        List<DcaCondition> conds = List.of(
                rsi("35.00"),
                new DcaCondition(DcaIndicator.SMA, DcaOperator.LT, 200, null),
                new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, new BigDecimal("-5.50")));
        dcaDetail.save(versionId, configWith(conds));
        assertThat(dcaDetail.findActiveByStrategyId(strategyId).orElseThrow().rules().getFirst().conditions()).containsExactlyElementsOf(conds);
        assertThat(dcaDetail.findActiveByStrategyIds(List.of(strategyId)).get(strategyId).rules().getFirst().conditions()).containsExactlyElementsOf(conds);
    }

    @Test
    void resave_replaces_rules() {
        // 같은 version 재저장 — 규칙 2개 → 1개로 교체되어도 UNIQUE 충돌 없음
        dcaDetail.save(versionId, new DcaStrategyConfig(List.of(monthlyBuy(K1, List.of(rsi("30.00"))), monthlyBuy(K2, List.of()))));
        dcaDetail.save(versionId, configWith(List.of(rsi("50.00"))));
        assertThat(dcaDetail.findActiveByStrategyId(strategyId).orElseThrow()).isEqualTo(configWith(List.of(rsi("50.00"))));
    }

    @Test
    void save_returns_values_normalized_to_db_scale() {
        // 입력 scale(0)이 달라도 save() 반환값은 조회와 같은 scale 2
        DcaRule raw = rule(K1, OrderDirection.BUY, DcaFrequency.DAILY, null, OrderType.MOC, null, amount("300"), List.of(rsi("35")));
        DcaStrategyConfig saved = dcaDetail.save(versionId, new DcaStrategyConfig(List.of(raw)));
        assertThat(saved.rules().getFirst().conditions().getFirst().threshold()).isEqualByComparingTo("35").hasScaleOf(2);
        assertThat(saved.rules().getFirst().sizing().amount()).hasScaleOf(2);
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
    void saveAndLoad_cryptoRuleWithExecutionHour() {
        DcaRule crypto = rule(K1, OrderDirection.BUY, DcaFrequency.WEEKLY, DayOfWeek.SUNDAY, OrderType.MARKET, 23,
                amount("10000.00"), List.of());
        dcaDetail.save(versionId, new DcaStrategyConfig(List.of(crypto)));
        em.flush();
        em.clear();

        DcaRule loaded = dcaDetail.findActiveByStrategyId(strategyId).orElseThrow().rules().getFirst();
        assertThat(loaded).isEqualTo(crypto);
    }
}
