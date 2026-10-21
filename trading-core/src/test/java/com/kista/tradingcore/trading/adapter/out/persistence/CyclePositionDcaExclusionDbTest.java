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

// 대시보드 최근/계좌 커서 조회는 DCA cycle_position을 제외하고 통계 범위 조회는 포함하는지 실 DB로 검증
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
        UUID dcaPos1 = insertPosition(accountId, "DCA");
        UUID dcaPos2 = insertPosition(accountId, "DCA");

        Instant from = Instant.EPOCH;
        Instant to = Instant.now().plusSeconds(3600);

        assertThat(repo.findRecentByUserId(userId, PageRequest.of(0, 10))).extracting(CyclePositionEntity::getId)
                .containsExactly(infinitePos);
        // 통계용 범위 조회는 DCA 포함 (원금을 누적 순투입액으로 계산해 통계에 포함)
        assertThat(repo.findByUserIdAndRange(userId, from, to)).extracting(CyclePositionEntity::getId)
                .containsExactlyInAnyOrder(infinitePos, dcaPos1, dcaPos2);
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
