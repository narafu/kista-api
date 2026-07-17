package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// findByStrategyIds — 통계용 여러 전략 사이클 배치 조회 검증 (raw JDBC로 created_at·deleted_at 직접 통제)
@Import(StrategyCyclePersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class StrategyCyclePersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StrategyCyclePersistenceAdapter cycleAdapter;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
    }

    // strategy → strategy_version 체인 삽입, strategyId 반환 (버전은 1개만 필요)
    private UUID insertStrategy() {
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "INFINITE", "SOXL", "ACTIVE", "NONE");
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        return strategyId;
    }

    private void insertCycle(UUID cycleId, UUID strategyId, Instant createdAt, boolean deleted) {
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at, deleted_at) "
                        + "VALUES (?, ?, (SELECT id FROM strategy_version WHERE strategy_id = ?), ?, ?, ?, ?)",
                cycleId, strategyId, strategyId, new BigDecimal("1000.00"), LocalDate.now(),
                Timestamp.from(createdAt), deleted ? Timestamp.from(createdAt) : null);
    }

    @Test
    void findByStrategyIds_returnsCyclesAcrossStrategiesOrderedByCreatedAtExcludingDeleted() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(1, ChronoUnit.HOURS);
        UUID strategyA = insertStrategy();
        UUID strategyB = insertStrategy();
        UUID otherStrategy = insertStrategy(); // 조회 대상에 포함되지 않는 전략

        UUID cycleA1 = UUID.randomUUID();
        UUID cycleA2 = UUID.randomUUID();
        UUID cycleB1 = UUID.randomUUID();
        UUID deletedCycle = UUID.randomUUID();
        UUID otherCycle = UUID.randomUUID();

        // 등록 순서를 뒤섞어 createdAt ASC 정렬이 insert 순서가 아님을 검증
        insertCycle(cycleA2, strategyA, base.plus(2, ChronoUnit.HOURS), false);
        insertCycle(cycleA1, strategyA, base, false);
        insertCycle(cycleB1, strategyB, base.plus(1, ChronoUnit.HOURS), false);
        insertCycle(deletedCycle, strategyA, base.plus(3, ChronoUnit.HOURS), true); // 삭제 — 제외
        insertCycle(otherCycle, otherStrategy, base, false); // 조회 대상 전략 아님 — 제외

        List<StrategyCycle> result = cycleAdapter.findByStrategyIds(List.of(strategyA, strategyB));

        assertThat(result).extracting(StrategyCycle::id)
                .containsExactly(cycleA1, cycleB1, cycleA2);
        assertThat(result).extracting(StrategyCycle::deletedAt).allMatch(java.util.Objects::isNull);
    }

    @Test
    void findByStrategyIds_emptyCollection_returnsEmptyList() {
        assertThat(cycleAdapter.findByStrategyIds(List.of())).isEmpty();
    }
}
