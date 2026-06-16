package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy;

public interface NotifyPort {
    void notifyMarketClosed();
    void notifyInsufficientBalance(Account account, AccountBalance b, Strategy.Ticker ticker);
    void notifyError(Exception e);
}
