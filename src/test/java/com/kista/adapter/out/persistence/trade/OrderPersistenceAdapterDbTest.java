package com.kista.adapter.out.persistence.trade;

import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// sumFilledBuyAmountByCycleId SQL 필터(direction/status/cycle) 실효성 검증 — 실 DB 왕복 테스트
@Import(OrderPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class OrderPersistenceAdapterDbTest extends DataJpaTestBase {

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OrderPersistenceAdapter adapter;

    private UUID accountId;
    private UUID cycleId;    // 합산 대상 사이클
    private UUID otherCycleId; // 제외 대상 타 사이클

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        UUID strategyId = UUID.randomUUID();
        UUID strategyVersionId = UUID.randomUUID();
        cycleId = UUID.randomUUID();
        otherCycleId = UUID.randomUUID();

        // FK 선행 행: user → account → strategy → strategy_version → strategy_cycle
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, broker, account_no, broker_account_code, app_key, secret_key, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "KIS", "74420614", "01", "key", "secret");
        jdbcTemplate.update(
                "INSERT INTO strategy (id, account_id, type, ticker, status, cycle_seed_type, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                strategyId, accountId, "VR", "SOXL", "ACTIVE", "NONE");
        jdbcTemplate.update(
                "INSERT INTO strategy_version (id, strategy_id, version_no, created_at) VALUES (?, ?, ?, now())",
                strategyVersionId, strategyId, 1);
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) VALUES (?, ?, ?, ?, ?, now())",
                cycleId, strategyId, strategyVersionId, new BigDecimal("1000.00"), LocalDate.now());
        jdbcTemplate.update(
                "INSERT INTO strategy_cycle (id, strategy_id, strategy_version_id, start_amount, start_date, created_at) VALUES (?, ?, ?, ?, ?, now())",
                otherCycleId, strategyId, strategyVersionId, new BigDecimal("2000.00"), LocalDate.now());
    }

    // 주문 1건 직접 삽입 헬퍼
    private void insertOrder(UUID cId, String direction, String status,
                             Integer filledQuantity, BigDecimal filledPrice) {
        jdbcTemplate.update(
                "INSERT INTO orders (account_id, strategy_cycle_id, trade_date, ticker, order_type, timing, direction, price, quantity, status, filled_quantity, filled_price, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, cId, LocalDate.now(), "SOXL", "LOC", "AT_CLOSE",
                direction, new BigDecimal("22.00"), 5, status, filledQuantity, filledPrice);
    }

    @Test
    void sumFilledBuyAmountByCycleId_sumsOnlyFilledAndPartiallyFilledBuy() {
        // 대상 사이클: FILLED BUY 2건 + PARTIALLY_FILLED BUY 1건 + FILLED SELL 1건 + PLANNED BUY 1건
        insertOrder(cycleId, "BUY",  "FILLED",           5, new BigDecimal("10.00")); // 50.00
        insertOrder(cycleId, "BUY",  "FILLED",           3, new BigDecimal("20.00")); // 60.00
        insertOrder(cycleId, "BUY",  "PARTIALLY_FILLED", 2, new BigDecimal("15.00")); // 30.00
        insertOrder(cycleId, "SELL", "FILLED",           4, new BigDecimal("25.00")); // 제외 (SELL)
        insertOrder(cycleId, "BUY",  "PLANNED",          null, null);                 // 제외 (status 불일치)

        // 타 사이클: FILLED BUY 1건 — cycle_id 필터로 제외
        insertOrder(otherCycleId, "BUY", "FILLED", 10, new BigDecimal("30.00")); // 제외 (다른 사이클)

        // 기대값: 50.00 + 60.00 + 30.00 = 140.00
        BigDecimal result = adapter.sumFilledBuyAmountByCycleId(cycleId);

        assertThat(result).isEqualByComparingTo(new BigDecimal("140.00"));
    }
}
