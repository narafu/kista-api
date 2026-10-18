package com.kista.shared.kernel;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

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
    void cryptoTickersAreDcaOnlyCryptoMarket() {
        assertThat(StrategyTicker.BTC.getMarket()).isEqualTo(Market.CRYPTO_KRW);
        assertThat(StrategyTicker.ETH.getMarket()).isEqualTo(Market.CRYPTO_KRW);
        assertThat(StrategyTicker.BTC.getTargetProfitRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(StrategyTicker.SOXL.getMarket()).isEqualTo(Market.US_STOCK);
        assertThat(StrategyType.DCA.availableTickers()).contains(StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(StrategyType.INFINITE.availableTickers()).doesNotContain(StrategyTicker.BTC, StrategyTicker.ETH);
        assertThat(StrategyType.DCA.capability().orderTypes()).containsExactly(OrderType.MOO, OrderType.MOC, OrderType.MARKET);
    }

    @Test
    void newSkipReasonsHaveKoreanLabels() {
        assertThat(DcaSkipReason.BELOW_MIN_ORDER_AMOUNT.getLabel()).isEqualTo("최소 주문 금액 미달");
        assertThat(DcaSkipReason.EXCHANGE_API_ERROR.getLabel()).isEqualTo("거래소 API 오류");
        assertThat(DcaSkipReason.API_KEY_EXPIRED.getLabel()).isEqualTo("API 키 만료");
    }

    @Test
    void currencyLivesInKernel() {
        assertThat(Currency.tryParse(" KRW ")).contains(Currency.KRW);
        assertThat(Currency.tryParse("EUR")).isEmpty();
    }
}
