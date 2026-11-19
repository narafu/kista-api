package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class KernelVocabularyTest {

    @Test
    void brokersQuoteUsd() {
        for (Broker b : Broker.values()) assertThat(b.getCurrency()).isEqualTo(Currency.USD);
    }

    @Test
    void dcaTickerCapabilities() {
        assertThat(StrategyTicker.BITO.getTargetProfitRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(StrategyType.DCA.availableTickers()).contains(StrategyTicker.BITO, StrategyTicker.EETH);
        assertThat(StrategyType.INFINITE.availableTickers()).doesNotContain(StrategyTicker.BITO, StrategyTicker.EETH);
        assertThat(StrategyType.DCA.capability().orderTypes()).containsExactly(OrderType.MOO, OrderType.MOC);
    }

    @Test
    void currencyLivesInKernel() {
        assertThat(Currency.tryParse(" KRW ")).contains(Currency.KRW);
        assertThat(Currency.tryParse("EUR")).isEmpty();
    }

    @Test
    void usdAmountScale() {
        assertThat(Currency.USD.amountScale()).isEqualTo(2);
    }

    @Test
    void brokerCapabilityFlags() {
        assertThat(Broker.MERITZ.isKeyOnlyCredentials()).isTrue();
        for (Broker b : new Broker[]{Broker.TOSS, Broker.MOCK}) assertThat(b.isKeyOnlyCredentials()).isFalse();
        assertThat(Arrays.stream(Broker.values()).filter(Broker::isApiKeyExpiring))
                .containsExactlyInAnyOrder(Broker.TOSS, Broker.MERITZ);
        assertThat(Stream.of(Broker.TOSS, Broker.MERITZ).map(Broker::getApiKeyExpirySource))
                .containsOnly(ApiKeyExpirySource.ESTIMATED);
        assertThat(Broker.MOCK.getApiKeyExpirySource()).isEqualTo(ApiKeyExpirySource.NONE);
    }
}
