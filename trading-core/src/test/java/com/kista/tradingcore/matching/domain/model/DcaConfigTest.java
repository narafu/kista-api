package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.Collections;
import java.util.List;

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

    @Test
    void market_order_requires_execution_hour_and_allows_weekend() {
        DcaConfig crypto = new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.WEEKLY, DayOfWeek.SUNDAY, null, List.of(), 9);
        assertThat(crypto.executionHour()).isEqualTo(9);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("executionHour");
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, new BigDecimal("10000"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 24))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0~23");
        // 주식 주문유형은 주말·실행 시각을 허용하지 않는다
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC,
                DcaFrequency.WEEKLY, DayOfWeek.SUNDAY, null, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC,
                DcaFrequency.DAILY, null, null, List.of(), 9))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requireCompatible_enforces_market_rules() {
        DcaConfig crypto = new DcaConfig(OrderDirection.BUY, new BigDecimal("5000"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 0);
        crypto.requireCompatible(Market.CRYPTO_KRW);
        assertThatThrownBy(() -> crypto.requireCompatible(Market.US_STOCK)).hasMessageContaining("MOO 또는 MOC");
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, AMOUNT, OrderType.MOC, DcaFrequency.DAILY, null, null)
                .requireCompatible(Market.CRYPTO_KRW)).hasMessageContaining("MARKET");
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, new BigDecimal("4999"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 0).requireCompatible(Market.CRYPTO_KRW)).hasMessageContaining("5,000");
        assertThatThrownBy(() -> new DcaConfig(OrderDirection.BUY, new BigDecimal("5000.5"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 0).requireCompatible(Market.CRYPTO_KRW)).hasMessageContaining("정수");
        new DcaConfig(OrderDirection.BUY, new BigDecimal("5000.00"), OrderType.MARKET,
                DcaFrequency.DAILY, null, null, List.of(), 0).requireCompatible(Market.CRYPTO_KRW); // 5000.00은 정수 값
    }
}
