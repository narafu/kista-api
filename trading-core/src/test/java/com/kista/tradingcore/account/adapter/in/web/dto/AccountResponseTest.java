package com.kista.tradingcore.account.adapter.in.web.dto;

import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.support.TradingFixtures;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AccountResponseTest {

    @Test
    void currencyIsDerivedFromBroker() {
        Account kis = TradingFixtures.kisAccount(UUID.randomUUID(), UUID.randomUUID());
        assertThat(AccountResponse.from(kis).currency()).isEqualTo(Broker.KIS.getCurrency().name());
    }
}
