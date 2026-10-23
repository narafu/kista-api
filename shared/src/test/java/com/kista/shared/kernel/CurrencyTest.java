package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyTest {

    @Test
    void amountScaleIsSingleSource() {
        assertThat(Currency.KRW.amountScale()).isZero();
        assertThat(Currency.JPY.amountScale()).isZero();
        assertThat(Currency.USD.amountScale()).isEqualTo(2);
        assertThat(Currency.USDT.amountScale()).isEqualTo(2);
    }

    @Test
    void roundUsesCurrencyScale() {
        assertThat(Currency.KRW.round(new BigDecimal("14999.5"))).isEqualTo(new BigDecimal("15000"));
        assertThat(Currency.USDT.round(new BigDecimal("10.4000192"))).isEqualTo(new BigDecimal("10.40")); // isEqualTo는 scale까지 비교
    }

    @Test
    void formatKeepsUpbitWording() {
        assertThat(Currency.KRW.format(new BigDecimal("150000000"))).isEqualTo("150,000,000원");
        assertThat(Currency.KRW.format(new BigDecimal("5000"))).isEqualTo("5,000원");
        assertThat(Currency.USDT.format(new BigDecimal("10.5"))).isEqualTo("10.50 USDT");
    }

    @Test
    void tryParseKnowsUsdt() {
        assertThat(Currency.tryParse("USDT")).contains(Currency.USDT);
    }
}
