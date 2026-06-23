package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.strategy.Strategy.Ticker;

// 판매 가능 수량 조회 — KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity
public interface SellableQuantityPort {
    SellableQuantity getSellableQuantity(Ticker ticker, Account account);
}
