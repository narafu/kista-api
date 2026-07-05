package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.domain.model.strategy.StrategyVrDetail;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import({
        StrategyPersistenceAdapter.class,
        StrategyVersionPersistenceAdapter.class,
        StrategyCyclePersistenceAdapter.class,
        StrategyVrDetailPersistenceAdapter.class
})
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class StrategyVrDetailPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;
    @Autowired StrategyPersistenceAdapter strategyAdapter;
    @Autowired StrategyVersionPersistenceAdapter strategyVersionAdapter;
    @Autowired StrategyCyclePersistenceAdapter strategyCycleAdapter;
    @Autowired StrategyVrDetailPersistenceAdapter vrDetailAdapter;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        accountId = UUID.randomUUID();

        // FK 선행 행 — 같은 패키지 리포지토리가 없는 user/account는 JdbcTemplate 직접 삽입
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
    }

    @Test
    void save_andFindByVersionId_roundTrip() {
        // strategy_version 선행 저장 (FK 필요)
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
        ));
        StrategyVersion version = strategyVersionAdapter.save(
                new StrategyVersion(null, strategy.id(), 1, null, null));

        StrategyVrDetail detail = new StrategyVrDetail(
                version.id(), 4, new BigDecimal("15.00"), 100);

        // RED → GREEN: save 후 findByVersionId로 왕복 검증
        StrategyVrDetail saved = vrDetailAdapter.save(detail);
        Optional<StrategyVrDetail> found = vrDetailAdapter.findByStrategyVersionId(version.id());

        assertThat(saved.strategyVersionId()).isEqualTo(version.id());
        assertThat(saved.intervalWeeks()).isEqualTo(4);
        assertThat(saved.bandWidth()).isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(saved.recurringAmount()).isEqualTo(100);
        assertThat(found).isPresent();
        assertThat(found.get()).isEqualTo(saved);
    }

    @Test
    void findByStrategyVersionId_returnsEmptyIfNotExists() {
        // 존재하지 않는 버전 ID → Optional.empty
        Optional<StrategyVrDetail> result = vrDetailAdapter.findByStrategyVersionId(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void findActiveByStrategyId_returnsLatestActiveVersion() {
        // 활성 버전(deleted_at IS NULL)의 최신 VR 상세 조회
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
        ));
        StrategyVersion v1 = strategyVersionAdapter.save(
                new StrategyVersion(null, strategy.id(), 1, null, null));
        StrategyVersion v2 = strategyVersionAdapter.save(
                new StrategyVersion(null, strategy.id(), 2, null, null));

        vrDetailAdapter.save(new StrategyVrDetail(v1.id(), 4, new BigDecimal("10.00"), 0));
        StrategyVrDetail latestDetail = new StrategyVrDetail(v2.id(), 8, new BigDecimal("20.00"), 200);
        vrDetailAdapter.save(latestDetail);

        // 활성 버전 중 created_at DESC LIMIT 1 — v2가 나중에 생성되므로 v2 반환
        Optional<StrategyVrDetail> result = vrDetailAdapter.findActiveByStrategyId(strategy.id());

        assertThat(result).isPresent();
        assertThat(result.get().strategyVersionId()).isEqualTo(v2.id());
        assertThat(result.get().intervalWeeks()).isEqualTo(8);
        assertThat(result.get().bandWidth()).isEqualByComparingTo(new BigDecimal("20.00"));
        assertThat(result.get().recurringAmount()).isEqualTo(200);
    }

    @Test
    void findActiveByStrategyId_skipsDeletedVersion() {
        // v2가 soft-delete(deleted_at 설정)된 경우 v1 VR 상세를 반환하는지 검증
        // sv.deleted_at IS NULL 필터 제거 회귀를 잡는 케이스
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
        ));
        StrategyVersion v1 = strategyVersionAdapter.save(
                new StrategyVersion(null, strategy.id(), 1, null, null));
        StrategyVersion v2 = strategyVersionAdapter.save(
                new StrategyVersion(null, strategy.id(), 2, null, null));

        vrDetailAdapter.save(new StrategyVrDetail(v1.id(), 4, new BigDecimal("10.00"), 100));
        vrDetailAdapter.save(new StrategyVrDetail(v2.id(), 8, new BigDecimal("20.00"), 200));

        // JPA flush 후 v2 soft-delete 직접 적용 (adapter 메서드는 active 전체 삭제라 v1도 포함됨)
        entityManager.flush();
        jdbcTemplate.update("UPDATE strategy_version SET deleted_at = now() WHERE id = ?", v2.id());
        entityManager.clear(); // L1 캐시 비움 — 이후 native 쿼리가 DB 최신 상태 반영

        // deleted_at IS NULL 필터 → v2 제외, v1 VR 상세 반환
        Optional<StrategyVrDetail> result = vrDetailAdapter.findActiveByStrategyId(strategy.id());

        assertThat(result).isPresent();
        assertThat(result.get().strategyVersionId()).isEqualTo(v1.id());
        assertThat(result.get().intervalWeeks()).isEqualTo(4);
        assertThat(result.get().bandWidth()).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(result.get().recurringAmount()).isEqualTo(100);
    }

    @Test
    void findActiveByStrategyId_returnsEmptyIfNoStrategy() {
        // 존재하지 않는 전략 ID → Optional.empty
        Optional<StrategyVrDetail> result = vrDetailAdapter.findActiveByStrategyId(UUID.randomUUID());

        assertThat(result).isEmpty();
    }

    @Test
    void save_upsert_updatesExistingRecord() {
        // 동일 strategyVersionId로 재저장 시 upsert(update) 동작 확인
        Strategy strategy = strategyAdapter.save(new Strategy(
                null, accountId, Strategy.Type.VR,
                Strategy.Status.ACTIVE, Strategy.Ticker.SOXL, Strategy.CycleSeedType.NONE
        ));
        StrategyVersion version = strategyVersionAdapter.save(
                new StrategyVersion(null, strategy.id(), 1, null, null));

        vrDetailAdapter.save(new StrategyVrDetail(version.id(), 4, new BigDecimal("15.00"), 100));
        // 같은 PK로 다시 저장 — intervalWeeks 변경
        StrategyVrDetail updated = vrDetailAdapter.save(
                new StrategyVrDetail(version.id(), 8, new BigDecimal("25.00"), 50));

        Optional<StrategyVrDetail> found = vrDetailAdapter.findByStrategyVersionId(version.id());
        assertThat(found).isPresent();
        assertThat(found.get().intervalWeeks()).isEqualTo(8);
        assertThat(updated.bandWidth()).isEqualByComparingTo(new BigDecimal("25.00"));

        // JPA 1차 캐시를 DB에 반영 후 native 쿼리로 중복 저장 여부 확인
        entityManager.flush();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM strategy_vr_version WHERE strategy_version_id = ?",
                Integer.class, version.id());
        assertThat(count).isEqualTo(1);
    }

    @Test
    void strategyVrVersionHasNoDeletedAt_confirmedBySchema() {
        // strategy_vr_version 테이블에 deleted_at 없음 — 부모 FK CASCADE로 처리
        Integer deletedAtCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_name = 'strategy_vr_version'
                  AND column_name = 'deleted_at'
                """, Integer.class);
        assertThat(deletedAtCount).isZero();
    }
}
