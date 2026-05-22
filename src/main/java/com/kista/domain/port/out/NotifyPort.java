package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.TradingReport;
import com.kista.domain.model.tradingcycle.TradingCycle;

public interface NotifyPort {
    void notifyReport(TradingReport r);
    void notifyMarketClosed();
    void notifyInsufficientBalance(Account account, AccountBalance b, TradingCycle.Ticker ticker);
    void notifyError(Exception e);
}
