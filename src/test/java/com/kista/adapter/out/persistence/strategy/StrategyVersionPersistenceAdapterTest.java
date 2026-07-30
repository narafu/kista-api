package com.kista.adapter.out.persistence.strategy;

import com.kista.domain.model.strategy.StrategyVersion;
import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// nextVersionNo — StrategyVersionEntity의 @SQLRestriction("deleted_at IS NULL")이
// 커스텀 @Query(findMaxVersionNoByStrategyId)에도 자동 적용되는 것을 검증한다.
// (CycleSnapshotCreator.reconfigureVrCycle이 "소프트 삭제 전에 nextVersionNo를 먼저 계산해야 하는" 이유)
@Import(StrategyVersionPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class StrategyVersionPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StrategyVersionPersistenceAdapter versionAdapter;

    private UUID accountId;
    private UUID strategyId;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        strategyId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "VR", "TQQQ", "ACTIVE", "NONE");
    }

    // 배치 조회 테스트 전용 — 계좌 내 추가 전략 삽입 (DB 레벨 계좌당 종목 유니크 제약 없음)
    private UUID insertStrategy() {
        UUID newStrategyId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                newStrategyId, accountId, "VR", "TQQQ", "ACTIVE", "NONE");
        return newStrategyId;
    }

    @Test
    void nextVersionNo_noExistingVersion_returns1() {
        assertThat(versionAdapter.nextVersionNo(strategyId)).isEqualTo(1);
    }

    @Test
    void nextVersionNo_activeVersionExists_returnsIncrementedNumber() {
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                UUID.randomUUID(), strategyId, 1);

        assertThat(versionAdapter.nextVersionNo(strategyId)).isEqualTo(2);
    }

    // 회귀 테스트 — 활성 버전을 먼저 소프트 삭제한 뒤 nextVersionNo를 계산하면 @SQLRestriction 때문에
    // 방금 삭제한 버전이 MAX(version_no) 계산에서 제외되어 동일한 번호가 다시 나온다.
    // CycleSnapshotCreator.reconfigureVrCycle이 이 순서를 지키지 않으면
    // uq_strategy_version_strategy_version_no 위반으로 재설정이 실패한다.
    @Test
    void nextVersionNo_afterSoftDeletingOnlyVersion_incorrectlyRestartsFrom1() {
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                UUID.randomUUID(), strategyId, 1);

        versionAdapter.softDeleteActiveByStrategyId(strategyId, Instant.now());

        assertThat(versionAdapter.nextVersionNo(strategyId)).isEqualTo(1);
    }

    @Test
    void findActiveByStrategyIds_returnsHighestVersionNoPerStrategyExcludingDeleted() {
        UUID strategyB = insertStrategy();
        UUID otherStrategy = insertStrategy(); // 조회 대상 아님

        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID(); // strategyId의 활성 최신 버전
        UUID deletedLatestB = UUID.randomUUID(); // strategyB의 더 큰 버전번호지만 삭제됨 — 제외
        UUID activeB = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                v1, strategyId, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                v2, strategyId, 2);
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                activeB, strategyB, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at, deleted_at) VALUES (?, ?, ?, now(), now())",
                deletedLatestB, strategyB, 2);
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                UUID.randomUUID(), otherStrategy, 1);

        Map<UUID, StrategyVersion> result = versionAdapter.findActiveByStrategyIds(List.of(strategyId, strategyB));

        assertThat(result).hasSize(2);
        assertThat(result.get(strategyId).id()).isEqualTo(v2);
        assertThat(result.get(strategyB).id()).isEqualTo(activeB);
        assertThat(result).doesNotContainKey(otherStrategy);
    }

    @Test
    void findActiveByStrategyIds_emptyCollection_returnsEmptyMap() {
        assertThat(versionAdapter.findActiveByStrategyIds(List.of())).isEmpty();
    }
}
