package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DcaConfigRequestTest {

    private static DcaRuleRequest rule(String amount, OrderType type, Integer hour) {
        return new DcaRuleRequest(null, OrderDirection.BUY, DcaFrequency.DAILY, null, null, type, hour,
                new DcaSizingRequest(DcaSizingType.AMOUNT, new BigDecimal(amount), null), DcaShortfallPolicy.PARTIAL, null);
    }

    @Test
    void rulesAreRequired() {
        assertThatThrownBy(() -> new DcaConfigRequest(null).toDomain())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("rules는 필수입니다.");
        assertThatThrownBy(() -> new DcaConfigRequest(Arrays.asList((DcaRuleRequest) null)).toDomain())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DcaConfigRequest(List.of()).toDomain()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionHourIsIgnoredForStockOrderTypes() {
        assertThat(new DcaConfigRequest(List.of(rule("300", OrderType.MOC, 9))).toDomain().rules().getFirst().executionHour()).isNull();
        assertThat(new DcaConfigRequest(List.of(rule("10000", OrderType.MARKET, 9))).toDomain().rules().getFirst().executionHour()).isEqualTo(9);
    }

    @Test
    void rulesKeepInputOrderAndSizing() {
        var config = new DcaConfigRequest(List.of(rule("300", OrderType.MOC, null), rule("500", OrderType.MOO, null))).toDomain();
        assertThat(config.rules()).extracting(r -> r.sizing().amount()).containsExactly(new BigDecimal("300"), new BigDecimal("500"));
    }
}
