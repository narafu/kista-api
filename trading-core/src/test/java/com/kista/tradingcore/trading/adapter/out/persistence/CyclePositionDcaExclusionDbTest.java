package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 사용자·계좌 단위 포지션 조회 3종이 DCA 전략의 cycle_position(공유 보유 스냅샷)을 제외하는지 실 DB로 검증
@Execution(ExecutionMode.SAME_THREAD)
class CyclePositionDcaExclusionDbTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbc;
    @Autowired CyclePositionJpaRepository repo;

    @Test
    void user_and_account_queries_exclude_dca_positions() {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        UUID infinitePos = insertPosition(accountId, "INFINITE");
        insertPosition(accountId, "DCA");
        insertPosition(accountId, "DCA");

        Instant from = Instant.EPOCH;
        Instant to = Instant.now().plusSeconds(3600);

        assertThat(repo.findRecentByUserId(userId, PageRequest.of(0, 10))).extracting(CyclePositionEntity::getId)
                .containsExactly(infinitePos);
        assertThat(repo.findByUserIdAndRange(userId, from, to)).extracting(CyclePositionEntity::getId)
                .containsExactly(infinitePos);
        assertThat(repo.findByAccountIdWithCursor(accountId, from, to, PageRequest.of(0, 10)))
                .extracting(CyclePositionEntity::getId).containsExactly(infinitePos);
    }

    // strategy → version → cycle → position 체인을 만들고 position id를 반환
    private UUID insertPosition(UUID accountId, String type) {
        UUID strategyId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID cycleId = UUID.randomUUID();
        UUID positionId = UUID.randomUUID();
        jdbc.update("INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, type, "SOXL", "ACTIVE", "NONE");
        jdbc.update("INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                versionId, strategyId, 1);
        jdbc.update("INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) VALUES (?, ?, ?, ?, ?, now())",
                cycleId, strategyId, versionId, new BigDecimal("1000.00"), LocalDate.now());
        jdbc.update("INSERT INTO cycle_position (id, strategy_cycle_id, usd_deposit, holdings, created_at) VALUES (?, ?, ?, ?, now())",
                positionId, cycleId, new BigDecimal("0.00"), 50);
        return positionId;
    }
}
