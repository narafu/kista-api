package com.kista.adapter.out.persistence;

import com.kista.domain.model.Order;
import com.kista.domain.model.TradeHistory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TradeHistoryPersistenceAdapterTest {

    @Autowired
    private TradeHistoryPersistenceAdapter adapter;

    private TradeHistory history(LocalDate date, String symbol, String kisOrderId) {
        return new TradeHistory(
                null, date, symbol, "SOXL_DIVISION",
                Order.OrderType.LOC, Order.OrderDirection.BUY,
                5, new BigDecimal("20.0000"), new BigDecimal("100.00"),
                Order.OrderStatus.PLACED, kisOrderId, null, null
        );
    }

    @Test
    void save_and_findBy_returns_matching_record() {
        LocalDate today = LocalDate.of(2024, 6, 15);
        TradeHistory h = history(today, "SOXL", "ORD-001");

        adapter.save(h);

        List<TradeHistory> result = adapter.findBy(today, today, "SOXL");

        assertThat(result).hasSize(1);
        TradeHistory saved = result.get(0);
        assertThat(saved.tradeDate()).isEqualTo(today);
        assertThat(saved.symbol()).isEqualTo("SOXL");
        assertThat(saved.strategy()).isEqualTo("SOXL_DIVISION");
        assertThat(saved.orderType()).isEqualTo(Order.OrderType.LOC);
        assertThat(saved.direction()).isEqualTo(Order.OrderDirection.BUY);
        assertThat(saved.qty()).isEqualTo(5);
        assertThat(saved.price()).isEqualByComparingTo("20.0000");
        assertThat(saved.amountUsd()).isEqualByComparingTo("100.00");
        assertThat(saved.status()).isEqualTo(Order.OrderStatus.PLACED);
        assertThat(saved.kisOrderId()).isEqualTo("ORD-001");
    }

    @Test
    void findBy_excludes_records_outside_date_range() {
        LocalDate inRange = LocalDate.of(2024, 6, 15);
        LocalDate outOfRange = LocalDate.of(2024, 6, 10);

        adapter.save(history(inRange, "SOXL", null));
        adapter.save(history(outOfRange, "SOXL", null));

        List<TradeHistory> result = adapter.findBy(inRange, inRange, "SOXL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).tradeDate()).isEqualTo(inRange);
    }

    @Test
    void save_allows_null_kisOrderId() {
        LocalDate today = LocalDate.of(2024, 6, 15);
        adapter.save(history(today, "SOXL", null));

        List<TradeHistory> result = adapter.findBy(today, today, "SOXL");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).kisOrderId()).isNull();
    }
}
