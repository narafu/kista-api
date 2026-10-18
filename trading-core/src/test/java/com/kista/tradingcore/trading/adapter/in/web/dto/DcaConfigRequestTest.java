package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DcaConfigRequestTest {

    private static DcaConfigRequest request(String amount, OrderType type, Integer hour) {
        return new DcaConfigRequest(OrderDirection.BUY, amount == null ? null : new BigDecimal(amount),
                type, DcaFrequency.DAILY, null, null, null, hour);
    }

    @Test
    void amountIsRequired() {
        assertThat(request("300", OrderType.MOC, null).toDomain().amount()).isEqualByComparingTo("300");
        assertThatThrownBy(() -> request(null, OrderType.MOC, null).toDomain())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("amount는 필수입니다.");
    }

    @Test
    void executionHourIsIgnoredForStockOrderTypes() {
        assertThat(request("300", OrderType.MOC, 9).toDomain().executionHour()).isNull();
        assertThat(request("10000", OrderType.MARKET, 9).toDomain().executionHour()).isEqualTo(9);
    }
}
