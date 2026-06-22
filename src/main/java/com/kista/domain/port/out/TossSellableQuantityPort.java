package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.strategy.Strategy.Ticker;

public interface TossSellableQuantityPort {
    // GET /api/v1/sellable-quantity?symbol={ticker}
    SellableQuantity getSellableQuantity(Ticker ticker, Account account);
}
