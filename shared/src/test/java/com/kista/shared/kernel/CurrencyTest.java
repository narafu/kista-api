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
    }

    @Test
    void roundUsesCurrencyScale() {
        assertThat(Currency.KRW.round(new BigDecimal("14999.5"))).isEqualTo(new BigDecimal("15000"));
        assertThat(Currency.USD.round(new BigDecimal("10.4000192"))).isEqualTo(new BigDecimal("10.40")); // isEqualTo는 scale까지 비교
    }

    @Test
    void truncateCutsToCurrencyScale() {
        assertThat(Currency.USD.truncate(new BigDecimal("10.409"))).isEqualTo(new BigDecimal("10.40")); // HALF_UP이면 10.41
        assertThat(Currency.USD.truncate(new BigDecimal("0.004"))).isEqualTo(new BigDecimal("0.00"));
        assertThat(Currency.KRW.truncate(new BigDecimal("14999.9"))).isEqualTo(new BigDecimal("14999"));
    }

    @Test
    void formatKeepsKrwWording() {
        assertThat(Currency.KRW.format(new BigDecimal("150000000"))).isEqualTo("150,000,000원");
        assertThat(Currency.KRW.format(new BigDecimal("5000"))).isEqualTo("5,000원");
    }

    @Test
    void displayUsesDollarForUsdAndFormatOtherwise() {
        assertThat(Currency.USD.display(new BigDecimal("1234.5"))).isEqualTo("$1,234.50");
        assertThat(Currency.KRW.display(new BigDecimal("15000"))).isEqualTo("15,000원");
    }
}
