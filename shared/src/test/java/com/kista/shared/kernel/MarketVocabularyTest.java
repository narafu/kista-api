package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MarketVocabularyTest {

    @Test
    void brokersCarryMarketAndCurrency() {
        for (Broker stock : new Broker[]{Broker.TOSS, Broker.MOCK}) {
            assertThat(stock.getMarket()).isEqualTo(Market.US_STOCK);
            assertThat(stock.getCurrency()).isEqualTo(Currency.USD);
        }
    }

    @Test
    void stockTickersTradeOnUsStock() {
        assertThat(StrategyTicker.SOXL.getMarkets()).containsExactly(Market.US_STOCK);
        assertThat(StrategyTicker.SOXL.tradableOn(Market.US_STOCK)).isTrue();
        assertThat(StrategyTicker.BITO.tradableOn(Market.US_STOCK)).isTrue();
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
    void marketCapabilities() {
        assertThat(Market.US_STOCK.quoteCurrency()).isEqualTo(Currency.USD);
        assertThat(Market.US_STOCK.minOrderAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(Market.US_STOCK.amountScale()).isEqualTo(2);
    }

    @Test
    void onlyUsStockQuotesUsd() { // USD 통계 합산의 포함식 전제 — 신규 시장이 USD 합계에 조용히 섞이지 않게
        assertThat(Arrays.stream(Market.values()).filter(m -> m.quoteCurrency() == Currency.USD)).containsExactly(Market.US_STOCK);
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
        for (Broker b : Broker.values()) assertThat(b.getCurrency()).isEqualTo(b.getMarket().quoteCurrency());
    }
}
