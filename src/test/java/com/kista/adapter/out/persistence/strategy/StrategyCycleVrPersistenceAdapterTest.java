package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyCycle;
import com.kista.domain.model.strategy.StrategyCycleVrDetail;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.support.DataJpaTestBase;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyVersionPersistenceAdapter.class,
        StrategyCyclePersistenceAdapter.class,
        StrategyCycleVrPersistenceAdapter.class
})
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class StrategyCycleVrPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired StrategyPersistenceAdapter strategyAdapter;
    @Autowired StrategyVersionPersistenceAdapter strategyVersionAdapter;
    @Autowired StrategyCyclePersistenceAdapter strategyCycleAdapter;
    @Autowired StrategyCycleVrPersistenceAdapter cycleVrAdapter;

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

    private StrategyCycle createCycle() {
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
        ));
        StrategyVersion version = strategyVersionAdapter.save(
                new StrategyVersion(null, strategy.id(), 1, null, null));
        return strategyCycleAdapter.save(new StrategyCycle(
                null, strategy.id(), version.id(),
                new BigDecimal("10000.00"), null,
                LocalDate.now(), null, null, null
        ));
    }

    @Test
    void save_andFindByCycleId_roundTrip() {
        StrategyCycle cycle = createCycle();
        StrategyCycleVrDetail detail = new StrategyCycleVrDetail(
                cycle.id(), new BigDecimal("5000.00"), 10, new BigDecimal("3000.00"));

        // RED → GREEN: save 후 findByCycleId 왕복 검증
        StrategyCycleVrDetail saved = cycleVrAdapter.save(detail);
        Optional<StrategyCycleVrDetail> found = cycleVrAdapter.findByCycleId(cycle.id());

        assertThat(saved.strategyCycleId()).isEqualTo(cycle.id());
        assertThat(saved.value()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(saved.gradient()).isEqualTo(10);
        assertThat(saved.poolLimit()).isEqualByComparingTo(new BigDecimal("3000.00"));
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(saved);
    }

    @Test
    void findByCycleId_returnsEmptyIfNotExists() {
        // 존재하지 않는 사이클 ID → Optional.empty
        Optional<StrategyCycleVrDetail> result = cycleVrAdapter.findByCycleId(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void save_upsert_updatesExistingRecord() {
        // 동일 strategyCycleId로 재저장 시 upsert(update) 동작 — value 변경
        StrategyCycle cycle = createCycle();
        cycleVrAdapter.save(new StrategyCycleVrDetail(
                cycle.id(), new BigDecimal("5000.00"), 10, new BigDecimal("3000.00")));

        StrategyCycleVrDetail updated = cycleVrAdapter.save(new StrategyCycleVrDetail(
                cycle.id(), new BigDecimal("6000.00"), 20, new BigDecimal("4000.00")));

        Optional<StrategyCycleVrDetail> found = cycleVrAdapter.findByCycleId(cycle.id());
        assertThat(found).isPresent();
        assertThat(found.get().value()).isEqualByComparingTo(new BigDecimal("6000.00"));
        assertThat(updated.gradient()).isEqualTo(20);

        // JPA 1차 캐시를 DB에 반영 후 native 쿼리로 중복 저장 여부 확인
        entityManager.flush();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy_cycle_vr WHERE strategy_cycle_id = ?",
                Integer.class, cycle.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void strategyCycleVrHasNoDeletedAt_confirmedBySchema() {
        // strategy_cycle_vr 테이블에 deleted_at 없음 — 부모 FK CASCADE로 처리
        Integer deletedAtCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'strategy_cycle_vr'
                  AND column_name = 'deleted_at'
                """, Integer.class);
        assertThat(deletedAtCount).isZero();
    }
}
