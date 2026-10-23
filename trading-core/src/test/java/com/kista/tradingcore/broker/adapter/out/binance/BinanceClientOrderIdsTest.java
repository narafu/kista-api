package com.kista.tradingcore.broker.adapter.out.binance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BinanceClientOrderIdsTest {

    @Test
    void roundTrip() {
        String id = "0192f0aa-1111-7222-8333-444455556666";
        assertThat(BinanceClientOrderIds.toWire(id)).isEqualTo("0192f0aa111172228333444455556666").hasSize(32);
        assertThat(BinanceClientOrderIds.fromWire("0192f0aa111172228333444455556666")).isEqualTo(id);
        assertThat(BinanceClientOrderIds.fromWire("web_abc")).isEqualTo("web_abc");
        assertThatIllegalArgumentException().isThrownBy(() -> BinanceClientOrderIds.toWire("not-a-uuid"));
    }
}
