package com.kista.tradingcore.privacy.adapter.out.persistence;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.privacy.domain.model.PrivacyDates;
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

// 기준표 발행일 ≠ 배치 거래일 — 거래일 T는 발행일 정확히 T-1 행만 쓴다(T-2·T 행이 있어도 끌어오지 않는다). 실 DB, 테스트 트랜잭션 롤백.
@Import(PrivacyTradePersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
class PrivacyTradeBaseJpaRepositoryDbTest extends DataJpaTestBase {

    // 운영 데이터와 겹치지 않도록 먼 미래 날짜 사용 — (release_date, ticker) UNIQUE 충돌 방지
    static final LocalDate TRADE_DATE = LocalDate.of(2099, 3, 10);

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PrivacyTradeBaseJpaRepository repository;
    @Autowired PrivacyTradePersistenceAdapter adapter;

    @Test
    void findWithOrdersByReleaseDateAndTicker_matchesExactReleaseDateOnly() {
        LocalDate releaseDate = PrivacyDates.releaseDateFor(TRADE_DATE);
        insertBase(releaseDate.minusDays(1)); // T-2: <= 회귀 시 잡힘
        insertBase(TRADE_DATE);               // T: >= 회귀 시 잡힘

        assertThat(repository.findWithOrdersByReleaseDateAndTicker(releaseDate, StrategyTicker.SOXL)).isEmpty();

        UUID exactId = insertBase(releaseDate);
        jdbcTemplate.update(
                "INSERT INTO trading_ref.privacy_trade_base_orders (id, privacy_trade_id, direction, order_type, price, quantity) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), exactId, OrderDirection.BUY.name(), OrderType.LOC.name(), new BigDecimal("30.00"), 3);

        var found = repository.findWithOrdersByReleaseDateAndTicker(releaseDate, StrategyTicker.SOXL);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(exactId);
        assertThat(found.get().getReleaseDate()).isEqualTo(releaseDate);
        assertThat(found.get().getOrders()).hasSize(1);
    }

    @Test
    void findTodayTrade_usesOnlyBaseReleasedOnPreviousDay() {
        LocalDate releaseDate = PrivacyDates.releaseDateFor(TRADE_DATE);
        insertBase(releaseDate.minusDays(1));
        insertBase(TRADE_DATE);

        assertThat(adapter.findTodayTrade(TRADE_DATE)).isEmpty();

        UUID exactId = insertBase(releaseDate);

        var trade = adapter.findTodayTrade(TRADE_DATE);
        assertThat(trade).isPresent();
        assertThat(trade.get().id()).isEqualTo(exactId);
    }

    private UUID insertBase(LocalDate releaseDate) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO trading_ref.privacy_trade_bases (id, release_date, ticker, current_cycle_start, current_cycle_realized_pnl, avg_price, holdings, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())",
                id, releaseDate, StrategyTicker.SOXL.name(), new BigDecimal("10000.00"), BigDecimal.ZERO, new BigDecimal("30.00"), 5);
        return id;
    }
}
