package com.kista.tradingcore.account.domain.model;

import com.kista.shared.kernel.Broker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    @Test
    void displayOrder_sorts_by_broker_rank_then_created_at() {
        Account kis = account(Broker.KIS, 1);
        Account meritzLater = account(Broker.MERITZ, 2);
        Account meritzEarlier = account(Broker.MERITZ, 1);
        Account mock = account(Broker.MOCK, 0);
        Account binance = account(Broker.BINANCE, 3);

        assertThat(List.of(kis, mock, meritzLater, binance, meritzEarlier).stream().sorted(Account.DISPLAY_ORDER).toList())
                .containsExactly(meritzEarlier, meritzLater, binance, kis, mock);
    }

    private static Account account(Broker broker, long createdEpochSecond) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), "n", null, "k", "s", null, broker,
                Instant.ofEpochSecond(createdEpochSecond), null);
    }
}
