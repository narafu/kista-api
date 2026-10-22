package com.kista.tradingcore.broker.adapter.out.meritz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MeritzResponseTest {

    @Test
    void whole_quantity_is_null_and_blank_safe() {
        assertThat(MeritzResponse.wholeQuantity(null)).isZero();
        assertThat(MeritzResponse.wholeQuantity(" ")).isZero();
        assertThat(MeritzResponse.wholeQuantity("417.0000000000")).isEqualTo(417);
    }
}
