package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.model.order.TradeHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TradeHistoryPersistenceAdapterTest {

    @Autowired
    private TradeHistoryPersistenceAdapter adapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID accountId;

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        // account_id FK 제약을 위해 users → accounts 순으로 삽입 (트랜잭션 롤백으로 정리)
        jdbcTemplate.update(
                "INSERT INTO users (id, kakao_id, status, role, created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                userId, "test_kakao_" + userId, "ACTIVE", "USER");
        jdbcTemplate.update(
                "INSERT INTO accounts (id, user_id, nickname, account_no, kis_app_key, kis_secret_key, kis_account_type, broker, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
                accountId, userId, "테스트계좌", "74420614", "appKey", "appSecret", "01", "KIS");
    }

    private TradeHistory history(LocalDate date, Ticker ticker, String orderId) {
        return new TradeHistory(
                null, date, ticker, "SOXL_DIVISION",
                Order.OrderType.LOC, Order.OrderDirection.BUY,
                5, new BigDecimal("20.0000"), new BigDecimal("100.00"),
                Order.OrderStatus.PLACED, orderId, accountId, null
        );
    }

    @Test
    void save_and_findBy_returns_matching_record() {
        LocalDate today = LocalDate.of(2024, 6, 15);
        TradeHistory h = history(today, Ticker.SOXL, "ORD-001");

        adapter.save(h);

        List<TradeHistory> result = adapter.findBy(today, today, Ticker.SOXL);

        assertThat(result).hasSize(1);
        TradeHistory saved = result.get(0);
        assertThat(saved.tradeDate()).isEqualTo(today);
        assertThat(saved.ticker()).isEqualTo(Ticker.SOXL);
        assertThat(saved.strategy()).isEqualTo("SOXL_DIVISION");
        assertThat(saved.orderType()).isEqualTo(Order.OrderType.LOC);
        assertThat(saved.direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(saved.quantity()).isEqualTo(5);
        assertThat(saved.price()).isEqualByComparingTo("20.0000");
        assertThat(saved.amountUsd()).isEqualByComparingTo("100.00");
        assertThat(saved.status()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(saved.orderId()).isEqualTo("ORD-001");
    }

    @Test
    void findBy_excludes_records_outside_date_range() {
        LocalDate inRange = LocalDate.of(2024, 6, 15);
        LocalDate outOfRange = LocalDate.of(2024, 6, 10);

        adapter.save(history(inRange, Ticker.SOXL, null));
        adapter.save(history(outOfRange, Ticker.SOXL, null));

        List<TradeHistory> result = adapter.findBy(inRange, inRange, Ticker.SOXL);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tradeDate()).isEqualTo(inRange);
    }

    @Test
    void save_allows_null_orderId() {
        LocalDate today = LocalDate.of(2024, 6, 15);
        adapter.save(history(today, Ticker.SOXL, null));

        List<TradeHistory> result = adapter.findBy(today, today, Ticker.SOXL);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).orderId()).isNull();
    }
}
