package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountResponseTest {

    @Test
    void currencyIsDerivedFromBroker() {
        Account toss = TradingFixtures.stockAccount(UUID.randomUUID(), UUID.randomUUID());
        assertThat(AccountResponse.from(toss, LocalDate.of(2026, 1, 6)).currency()).isEqualTo(Broker.TOSS.getCurrency().name());
    }
}
