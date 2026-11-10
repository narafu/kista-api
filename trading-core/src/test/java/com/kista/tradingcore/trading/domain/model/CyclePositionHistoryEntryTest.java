package com.kista.tradingcore.trading.domain.model;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.shared.kernel.StrategyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CyclePositionHistoryEntryTest {

    private static CyclePositionHistoryEntry entry(String deposit) {
        return new CyclePositionHistoryEntry(UUID.randomUUID(), UUID.randomUUID(), StrategyTicker.BTC, StrategyType.DCA,
                new BigDecimal(deposit), new BigDecimal("0.55"), new BigDecimal("0.53"), new BigDecimal("10"), Instant.now());
    }

    @Test
    void roundedTo_krw_roundsDepositOnly() {
        CyclePositionHistoryEntry r = entry("12345.67").roundedTo(Currency.KRW);

        assertThat(r.deposit()).hasToString("12346");
        assertThat(r.closingPrice()).hasToString("0.55");
        assertThat(r.avgPrice()).hasToString("0.53");
        assertThat(r.holdings()).isEqualByComparingTo("10");
    }

    @Test
    void roundedTo_usd_keepsScale2() {
        assertThat(entry("100.50").roundedTo(Currency.USD).deposit()).hasToString("100.50");
    }
}
