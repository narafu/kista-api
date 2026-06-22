package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.SellableQuantity;
import com.kista.domain.model.strategy.Strategy.Ticker;

// KIS 전용 판매 가능 수량 조회 — CTRP6504R 체결기준현재잔고에서 종목별 잔고수량 조회
public interface KisSellableQuantityPort {
    // CTRP6504R 체결기준현재잔고에서 종목별 잔고수량(= 해외주식 판매가능수량) 조회
    SellableQuantity getSellableQuantity(Ticker ticker, Account account);
}
