package com.kista.matching.domain.model;

import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlannedOrderTest {

    @Test
    void of_blankLeg_becomesUnknown() {
        PlannedOrder o = PlannedOrder.of(LocalDate.of(2026, 9, 9), StrategyTicker.SOXL,
                OrderType.LOC, OrderDirection.BUY, 3, new BigDecimal("10.00"));
        assertThat(o.orderLeg()).isEqualTo(PlannedOrder.UNKNOWN_LEG);
        assertThat(o.timing()).isEqualTo(OrderTiming.AT_CLOSE);
    }

    @Test
    void withPrice_replacesPriceOnly() {
        PlannedOrder o = PlannedOrder.of(LocalDate.of(2026, 9, 9), StrategyTicker.SOXL,
                OrderType.LOC, OrderDirection.BUY, 3, new BigDecimal("10.00"))
                .withPrice(new BigDecimal("9.50"));
        assertThat(o.price()).isEqualByComparingTo("9.50");
        assertThat(o.quantity()).isEqualTo(3);
    }

    @Test
    void leg_formatsPrefixAndIndex() {
        assertThat(PlannedOrder.leg("INFINITE_BUY", 2)).isEqualTo("INFINITE_BUY_02");
    }

    @Test
    void leg_rejectsNonPositiveIndex() {
        assertThatThrownBy(() -> PlannedOrder.leg("X", 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
