package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.strategy.Strategy.Ticker;

// 브로커 무관 판매 가능 수량 조회 — KIS: CTRP6504R 잔고수량 / Toss: /api/v1/sellable-quantity
public interface BrokerSellableQuantityPort {
    SellableQuantity getSellableQuantity(Ticker ticker, Account account);
}
