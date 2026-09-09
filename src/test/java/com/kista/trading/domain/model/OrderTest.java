package com.kista.trading.domain.model;

import com.kista.matching.domain.model.OrderDirection;
import com.kista.matching.domain.model.OrderTiming;
import com.kista.matching.domain.model.OrderType;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.StrategyTicker;
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
        assertThat(o.status()).isEqualTo(Order.OrderStatus.PLANNED);
        assertThat(o.externalOrderId()).isNull();
        assertThat(o.filledQuantity()).isNull();
        assertThat(o.filledPrice()).isNull();
        // 강등 결과가 원본과 동일 — 8개 필드 매핑 전부 고정
        assertThat(o.toPlanned()).isEqualTo(p);
    }
}
