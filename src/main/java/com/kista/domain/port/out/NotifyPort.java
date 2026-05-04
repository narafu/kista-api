package com.kista.domain.port.out;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingReport;

public interface NotifyPort {
    void notifyReport(TradingReport r);
    void notifyMarketClosed();
    void notifyInsufficientBalance(AccountBalance b);
    void notifyError(Exception e);
}
