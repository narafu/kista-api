package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;

public interface KisAccountPort {
    AccountBalance getBalance(Account account, Strategy.Ticker ticker);
}
