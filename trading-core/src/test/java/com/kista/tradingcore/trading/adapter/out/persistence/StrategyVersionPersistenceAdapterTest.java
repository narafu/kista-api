package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.tradingcore.trading.domain.model.StrategyVersion;
import com.kista.shared.support.DataJpaTestBase;
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
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "VR", "TQQQ", "ACTIVE", "NONE");
    }

    // 계좌당 활성 종목 유니크 인덱스 때문에 전략마다 다른 종목을 쓴다 — 테스트 인스턴스마다 0부터
    private int tickerSeq;

    private String nextTicker() {
        return com.kista.shared.kernel.StrategyTicker.values()[tickerSeq++ % com.kista.shared.kernel.StrategyTicker.values().length].name();
    }

    // 배치 조회 테스트 전용 — 계좌 내 추가 전략 삽입 (종목은 활성 유니크 인덱스 때문에 매번 다르게)
    private UUID insertStrategy() {
        UUID newStrategyId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                newStrategyId, accountId, "VR", nextTicker(), "ACTIVE", "NONE");
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

    // 삭제 행 포함 MAX — 활성 버전을 먼저 소프트 삭제해도 번호를 재사용하지 않는다(uq_strategy_version_strategy_version_no 충돌 방지)
    @Test
    void nextVersionNo_afterSoftDeletingOnlyVersion_returnsMaxPlus1() {
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                UUID.randomUUID(), strategyId, 1);

        versionAdapter.softDeleteActiveByStrategyId(strategyId, Instant.now());

        assertThat(versionAdapter.nextVersionNo(strategyId)).isEqualTo(2);
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
