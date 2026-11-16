package com.kista.tradingcore.account.domain.model;

import com.kista.shared.kernel.Broker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTest {

    @Test
    void dedupeKeyOf_meritz_is_app_key() {
        assertThat(Account.dedupeKeyOf(Broker.MERITZ, null, "K")).isEqualTo("K");
        assertThat(Account.dedupeKeyOf(Broker.TOSS, "123", "K")).isEqualTo("123");
    }

    @Test
    void displayOrder_sorts_by_broker_rank_then_created_at() {
        Account toss = account(Broker.TOSS, 1);
        Account meritzLater = account(Broker.MERITZ, 2);
        Account meritzEarlier = account(Broker.MERITZ, 1);
        Account mock = account(Broker.MOCK, 0);

        assertThat(List.of(toss, mock, meritzLater, meritzEarlier).stream().sorted(Account.DISPLAY_ORDER).toList())
                .containsExactly(meritzEarlier, meritzLater, toss, mock);
    }

    private static Account account(Broker broker, long createdEpochSecond) {
        return new Account(UUID.randomUUID(), UUID.randomUUID(), "n", null, "k", "s", null, broker,
                Instant.ofEpochSecond(createdEpochSecond), null);
    }
}
