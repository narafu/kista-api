package com.kista.tradingcore.broker.adapter.out.binance;

import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceSymbolRegistryTest {

    private final BinanceSymbolRegistry registry = new BinanceSymbolRegistry();

    @Test
    void everyUsdtTickerHasSymbol() {
        Arrays.stream(StrategyTicker.values()).filter(t -> t.tradableOn(Market.CRYPTO_USDT))
                .forEach(t -> assertThat(registry.symbolOf(t)).isEqualTo(t.name() + "USDT"));
    }

    @Test
    void assetLookup() {
        assertThat(registry.tickerOfAsset("BTC")).contains(StrategyTicker.BTC);
        assertThat(registry.tickerOfAsset("DOGE")).isEmpty();
        assertThat(registry.baseAssetOf(StrategyTicker.ETH)).isEqualTo("ETH");
    }
}
