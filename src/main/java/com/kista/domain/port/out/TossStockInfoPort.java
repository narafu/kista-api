package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossStockInfo;

public interface TossStockInfoPort {
    // GET /api/v1/stocks?symbol={ticker}
    TossStockInfo getStockInfo(Ticker ticker, Account account);
}
