package com.kista.tradingcore.broker.adapter.out.upbit;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UpbitMarketRegistryTest {

    private final UpbitMarketRegistry registry = new UpbitMarketRegistry();

    @Test
    void everyCryptoTickerHasKrwMarket() {
        Arrays.stream(StrategyTicker.values()).filter(t -> t.getMarket() == Market.CRYPTO_KRW)
                .forEach(t -> assertThat(registry.marketOf(t)).isEqualTo("KRW-" + t.name()));
    }

    @Test
    void reverseLookups() {
        assertThat(registry.tickerOfCurrency("BTC")).contains(StrategyTicker.BTC);
        assertThat(registry.tickerOfCurrency("XRP")).isEmpty();
        assertThat(registry.tickerOfMarket("KRW-ETH")).contains(StrategyTicker.ETH);
        assertThatThrownBy(() -> registry.marketOf(StrategyTicker.SOXL)).isInstanceOf(IllegalArgumentException.class);
    }
}
