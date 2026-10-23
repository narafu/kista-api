package com.kista.shared.kernel;

import com.kista.shared.kernel.CryptoTradingRules.FeeSide;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class CryptoTradingRulesTest {

    static final CryptoTradingRules UPBIT_LIKE = new CryptoTradingRules(new BigDecimal("0.0005"), FeeSide.QUOTE_SURCHARGE, new BigDecimal("5000"), new BigDecimal("0.00000001"));
    static final CryptoTradingRules BINANCE_LIKE = new CryptoTradingRules(new BigDecimal("0.001"), FeeSide.RECEIVED_ASSET, new BigDecimal("5"), new BigDecimal("0.00001"));

    @Test void quoteSurchargeAddsFee() { assertThat(UPBIT_LIKE.quoteSpendFor(new BigDecimal("15000"))).isEqualByComparingTo("15007.5"); }

    @Test void receivedAssetSpendsExactAmount() { assertThat(BINANCE_LIKE.quoteSpendFor(new BigDecimal("10.50"))).isEqualByComparingTo("10.50"); }

    @Test void affordableReversesFee() {
        assertThat(UPBIT_LIKE.affordableAmount(new BigDecimal("50000"), 0)).isEqualTo(new BigDecimal("49975"));
        assertThat(BINANCE_LIKE.affordableAmount(new BigDecimal("100.129"), 2)).isEqualTo(new BigDecimal("100.12"));
    }

    @Test void quantityScaleFromStep() {
        assertThat(UPBIT_LIKE.quantityScale()).isEqualTo(8);
        assertThat(BINANCE_LIKE.quantityScale()).isEqualTo(5);
    }

    @Test void rejectsNonPositiveStep() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CryptoTradingRules(BigDecimal.ZERO, FeeSide.RECEIVED_ASSET, BigDecimal.ONE, BigDecimal.ZERO));
    }
}
