package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DcaConfigTest {

    private static final BigDecimal AMOUNT = new BigDecimal("300");

    @Test
    void derives_timing_and_leg() {
        DcaConfig moo = new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOO, DcaFrequency.DAILY, null, null);
        assertThat(moo.timing()).isEqualTo(OrderTiming.AT_OPEN);
        assertThat(moo.orderLeg()).isEqualTo("DCA_BUY");
        DcaConfig moc = new DcaConfig(OrderDirection.SELL, AMOUNT, OrderType.MOC, DcaFrequency.DAILY, null, null);
        assertThat(moc.timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(moc.orderLeg()).isEqualTo("DCA_SELL");
    }

    @Test
    void rejects_invalid_combinations() {
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.LOC, DcaFrequency.DAILY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, BigDecimal.ZERO, OrderType.MOC, DcaFrequency.DAILY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.WEEKLY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.WEEKLY, DayOfWeek.SATURDAY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.MONTHLY, DayOfWeek.MONDAY, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.MONTHLY, null, 32))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.DAILY, null, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_more_than_ten_conditions() {
        DcaCondition rsi = new DcaCondition(DcaIndicator.RSI, DcaOperator.LTE, null, new BigDecimal("30"));
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.DAILY, null, null,
                Collections.nCopies(11, rsi))).isInstanceOf(IllegalArgumentException.class);
    }
}
