package com.kista.tradingcore.account.domain.model;

import com.kista.shared.kernel.Broker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    @Test
    void dedupeKeyOf_binance_is_app_key() {
        assertThat(Account.dedupeKeyOf(Broker.BINANCE, null, "K")).isEqualTo("K");
    }

    @Test
    void dedupeKeyOf_meritz_is_app_key() {
        assertThat(Account.dedupeKeyOf(Broker.MERITZ, null, "K")).isEqualTo("K");
        assertThat(Account.dedupeKeyOf(Broker.KIS, "123", "K")).isEqualTo("123");
    }
}
