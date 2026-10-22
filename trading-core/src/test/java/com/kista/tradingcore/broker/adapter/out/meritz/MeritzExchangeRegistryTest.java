package com.kista.tradingcore.broker.adapter.out.meritz;

import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeritzExchangeRegistryTest {

    private final MeritzExchangeRegistry registry = new MeritzExchangeRegistry();

    @Test
    void registry_maps_all_us_tickers() {
        assertThat(registry.vendorId(StrategyTicker.TQQQ)).isEqualTo("0537");
        assertThat(registry.marketCode(StrategyTicker.TQQQ)).isEqualTo("OQ");
        for (StrategyTicker t : new StrategyTicker[]{StrategyTicker.SOXL, StrategyTicker.USD, StrategyTicker.MAGX,
                StrategyTicker.UPRO, StrategyTicker.QLD}) {
            assertThat(registry.vendorId(t)).isEqualTo("0066");
            assertThat(registry.marketCode(t)).isEqualTo("AX");
        }
        assertThatThrownBy(() -> registry.vendorId(StrategyTicker.BTC)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> registry.marketCode(StrategyTicker.BTC)).isInstanceOf(NoSuchElementException.class);
    }
}
