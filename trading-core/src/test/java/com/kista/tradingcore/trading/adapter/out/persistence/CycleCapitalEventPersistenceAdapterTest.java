package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(CycleCapitalEventPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class CycleCapitalEventPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired CycleCapitalEventPersistenceAdapter adapter;

    private UUID cycleId;
    private UUID positionId;

    @BeforeEach
    void setUp() {
        UUID accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        positionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, UUID.randomUUID(), "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "INFINITE", "SOXL", "ACTIVE", "NONE");
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, open_amount, start_date, created_at) VALUES (?, ?, ?, ?, 0, ?, now())",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), LocalDate.now());
        jdbcTemplate.update(
                "INSERT INTO cycle_position (id, strategy_cycle_id, deposit, holdings, created_at) VALUES (?, ?, ?, ?, now())",
                positionId, cycleId, new BigDecimal("1000.00"), 0);
    }

    private CycleCapitalEvent event(Kind kind, String deposit, String holdings, String principal, Instant at) {
        return new CycleCapitalEvent(null, cycleId, positionId, kind,
                new BigDecimal(deposit), new BigDecimal(holdings), new BigDecimal(principal), at);
    }

    @Test
    void save_thenFindByCycleIds_returnsInOccurredOrder() {
        Instant early = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(2, ChronoUnit.HOURS);
        Instant late = early.plus(1, ChronoUnit.HOURS);
        // 늦은 것을 먼저 저장해 정렬이 insert 순서가 아님을 검증
        adapter.save(event(Kind.WITHDRAW, "-50.00", "-2.00000000", "-80.00", late));
        adapter.save(event(Kind.INJECT, "100.00", "0.00000000", "100.00", early));

        List<CycleCapitalEvent> result = adapter.findByCycleIds(Set.of(cycleId));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isNotNull();
        assertThat(result.get(0).kind()).isEqualTo(Kind.INJECT);
        assertThat(result.get(0).strategyCycleId()).isEqualTo(cycleId);
        assertThat(result.get(0).cyclePositionId()).isEqualTo(positionId);
        assertThat(result.get(0).depositDelta()).isEqualByComparingTo("100.00");
        assertThat(result.get(0).holdingsDelta()).isEqualByComparingTo("0");
        assertThat(result.get(0).principalDelta()).isEqualByComparingTo("100.00");
        assertThat(result.get(1).kind()).isEqualTo(Kind.WITHDRAW);
        assertThat(result.get(1).depositDelta()).isEqualByComparingTo("-50.00");
        assertThat(result.get(1).holdingsDelta()).isEqualByComparingTo("-2");
        assertThat(result.get(1).principalDelta()).isEqualByComparingTo("-80.00");
    }

    @Test
    void findByCycleIds_emptyInput_returnsEmpty() {
        assertThat(adapter.findByCycleIds(Set.of())).isEmpty();
    }
}
