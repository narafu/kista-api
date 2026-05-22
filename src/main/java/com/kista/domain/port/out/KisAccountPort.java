package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.tradingcycle.TradingCycle;

public interface KisAccountPort {
    AccountBalance getBalance(Account account, TradingCycle.Ticker ticker);
}
