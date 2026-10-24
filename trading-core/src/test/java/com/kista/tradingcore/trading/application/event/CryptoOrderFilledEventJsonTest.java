package com.kista.tradingcore.trading.application.event;

import com.kista.shared.kernel.Currency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CryptoOrderFilledEventJsonTest {

    JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void newRowRoundTrips() {
        CryptoOrderFilledEvent src = new CryptoOrderFilledEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "바낸",
                StrategyTicker.BTC, OrderDirection.BUY, new BigDecimal("0.00016"), new BigDecimal("65000.12"),
                new BigDecimal("10.50"), Currency.USDT);
        CryptoOrderFilledEvent back = mapper.readValue(mapper.writeValueAsString(src), CryptoOrderFilledEvent.class);
        assertThat(back).isEqualTo(src);
    }
}
