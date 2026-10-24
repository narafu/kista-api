package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MarketVocabularyTest {

    @Test
    void brokersCarryMarketAndCurrency() {
        assertThat(Broker.UPBIT.getMarket()).isEqualTo(Market.CRYPTO_KRW);
        assertThat(Broker.UPBIT.getCurrency()).isEqualTo(Currency.KRW);
        for (Broker stock : new Broker[]{Broker.KIS, Broker.TOSS, Broker.MOCK}) {
            assertThat(stock.getMarket()).isEqualTo(Market.US_STOCK);
            assertThat(stock.getCurrency()).isEqualTo(Currency.USD);
        }
        assertThat(Broker.UPBIT.supportsOpenAuctionOrders()).isFalse();
    }

    @Test
    void cryptoTickersTradeOnBothCryptoMarkets() {
        assertThat(StrategyTicker.BTC.getMarkets()).containsExactly(Market.CRYPTO_KRW, Market.CRYPTO_USDT);
        assertThat(StrategyTicker.ETH.tradableOn(Market.CRYPTO_USDT)).isTrue();
        assertThat(StrategyTicker.BTC.tradableOn(Market.US_STOCK)).isFalse();
        assertThat(StrategyTicker.SOXL.getMarkets()).containsExactly(Market.US_STOCK);
        assertThat(StrategyTicker.SOXL.tradableOn(Market.CRYPTO_USDT)).isFalse();
        assertThat(StrategyTicker.BTC.isCrypto()).isTrue();
        assertThat(StrategyTicker.SOXL.isCrypto()).isFalse();
        assertThat(StrategyTicker.BTC.getTargetProfitRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(StrategyType.DCA.availableTickers()).contains(StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(StrategyType.INFINITE.availableTickers()).doesNotContain(StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(StrategyType.DCA.capability().orderTypes()).containsExactly(OrderType.MOO, OrderType.MOC, OrderType.MARKET);
    }

    @Test
    void newSkipReasonsHaveKoreanLabels() {
        assertThat(DcaSkipReason.BELOW_MIN_ORDER_AMOUNT.getLabel()).isEqualTo("최소 주문 금액 미달");
        assertThat(DcaSkipReason.EXCHANGE_API_ERROR.getLabel()).isEqualTo("거래소 API 오류");
        assertThat(DcaSkipReason.API_KEY_EXPIRED.getLabel()).isEqualTo("API 키 인증 실패");
    }

    @Test
    void currencyLivesInKernel() {
        assertThat(Currency.tryParse(" KRW ")).contains(Currency.KRW);
        assertThat(Currency.tryParse("EUR")).isEmpty();
    }

    @Test
    void marketCapabilities() {
        assertThat(Market.US_STOCK.quoteCurrency()).isEqualTo(Currency.USD);
        assertThat(Market.CRYPTO_KRW.quoteCurrency()).isEqualTo(Currency.KRW);
        assertThat(Market.CRYPTO_USDT.quoteCurrency()).isEqualTo(Currency.USDT);
        assertThat(Market.US_STOCK.isCrypto()).isFalse();
        assertThat(Market.CRYPTO_KRW.isCrypto()).isTrue();
        assertThat(Market.CRYPTO_USDT.isCrypto()).isTrue();
        assertThat(Market.CRYPTO_KRW.minOrderAmount()).isEqualTo(new BigDecimal("5000"));
        assertThat(Market.CRYPTO_USDT.minOrderAmount()).isEqualTo(new BigDecimal("5"));
        assertThat(Market.US_STOCK.minOrderAmount()).isEqualTo(BigDecimal.ZERO);
        assertThat(Market.CRYPTO_KRW.amountScale()).isZero();
        assertThat(Market.CRYPTO_USDT.amountScale()).isEqualTo(2);
    }

    @Test
    void onlyUsStockQuotesUsd() { // USD 통계 합산의 포함식 전제 — 신규 시장이 USD 합계에 조용히 섞이지 않게
        assertThat(Arrays.stream(Market.values()).filter(m -> m.quoteCurrency() == Currency.USD)).containsExactly(Market.US_STOCK);
    }

    @Test
    void brokerCapabilityFlags() {
        assertThat(Broker.UPBIT.isKeyOnlyCredentials()).isTrue();
        assertThat(Broker.MERITZ.isKeyOnlyCredentials()).isTrue();
        assertThat(Broker.BINANCE.isKeyOnlyCredentials()).isTrue();
        assertThat(Broker.BINANCE.getCurrency()).isEqualTo(Currency.USDT);
        for (Broker b : new Broker[]{Broker.KIS, Broker.TOSS, Broker.MOCK}) assertThat(b.isKeyOnlyCredentials()).isFalse();
        assertThat(Arrays.stream(Broker.values()).filter(Broker::isApiKeyExpiring)).containsExactly(Broker.UPBIT);
        assertThat(Arrays.stream(Broker.values()).filter(Broker::isCryptoExchange)).containsExactly(Broker.UPBIT, Broker.BINANCE);
        for (Broker b : Broker.values()) assertThat(b.getCurrency()).isEqualTo(b.getMarket().quoteCurrency());
    }
}
