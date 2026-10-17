package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DcaConfigRequestTest {

    private static DcaConfigRequest request(String amount, String amountUsd, OrderType type, Integer hour) {
        return new DcaConfigRequest(OrderDirection.BUY, amount == null ? null : new BigDecimal(amount),
                amountUsd == null ? null : new BigDecimal(amountUsd), type, DcaFrequency.DAILY, null, null, null, hour);
    }

    @Test
    void amountOrLegacyAmountUsdIsAccepted() {
        assertThat(request("300", null, OrderType.MOC, null).toDomain().amount()).isEqualByComparingTo("300");
        assertThat(request(null, "300", OrderType.MOC, null).toDomain().amount()).isEqualByComparingTo("300");
    }

    @Test
    void sameValueWithDifferentScaleIsNotAConflict() {
        assertThat(request("300", "300.00", OrderType.MOC, null).toDomain().amount()).isEqualByComparingTo("300");
    }

    @Test
    void differentValuesOrNoneAreRejected() {
        assertThatThrownBy(() -> request("300", "301", OrderType.MOC, null).toDomain())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amountUsd");
        assertThatThrownBy(() -> request(null, null, OrderType.MOC, null).toDomain())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionHourIsIgnoredForStockOrderTypes() {
        assertThat(request("300", null, OrderType.MOC, 9).toDomain().executionHour()).isNull();
        assertThat(request("10000", null, OrderType.MARKET, 9).toDomain().executionHour()).isEqualTo(9);
    }
}
