package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.OrderStatus;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderTest {

    @Test
    void fromPlanned_mapsAllFields_thenToPlanned_roundTrips() {
        PlannedOrder p = PlannedOrder.of(LocalDate.of(2026, 9, 9), StrategyTicker.SOXL,
                OrderType.LOC, OrderDirection.BUY, 3, new BigDecimal("10.00"),
                OrderTiming.AT_OPEN, "INFINITE_BUY_01");
        UUID acc = UUID.randomUUID();
        UUID cyc = UUID.randomUUID();

        Order o = Order.fromPlanned(p, acc, cyc);

        assertThat(o.id()).isNull();
        assertThat(o.accountId()).isEqualTo(acc);
        assertThat(o.strategyCycleId()).isEqualTo(cyc);
        assertThat(o.status()).isEqualTo(OrderStatus.PLANNED);
        assertThat(o.externalOrderId()).isNull();
        assertThat(o.filledQuantity()).isNull();
        assertThat(o.filledPrice()).isNull();
        // 강등 결과가 원본과 동일 — 8개 필드 매핑 전부 고정
        assertThat(o.toPlanned()).isEqualTo(p);
    }

    @Test
    void cryptoMarketKeepsFractionalQuantity() {
        Order o = Order.cryptoMarket(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 10, 18),
                StrategyTicker.BTC, OrderDirection.BUY, "DCA_BUY", OrderTiming.AT_CLOSE,
                new BigDecimal("0.00012345"), new BigDecimal("150000000"));

        assertThat(o.quantity()).isEqualByComparingTo("0.00012345");
        assertThat(o.status()).isEqualTo(OrderStatus.PLANNED);
        assertThat(o.orderType()).isEqualTo(OrderType.MARKET);
        assertThat(o.id()).isNull();
        assertThat(o.externalOrderId()).isNull();
    }
}
