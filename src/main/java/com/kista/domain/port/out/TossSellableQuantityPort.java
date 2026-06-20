package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossSellableQuantity;

public interface TossSellableQuantityPort {
    // GET /api/v1/sellable-quantity?symbol={ticker}
    TossSellableQuantity getSellableQuantity(Ticker ticker, Account account);
}
