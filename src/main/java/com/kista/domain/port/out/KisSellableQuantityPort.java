package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.model.toss.TossSellableQuantity;

public interface KisSellableQuantityPort {
    // CTRP6504R 체결기준현재잔고에서 종목별 잔고수량(= 해외주식 판매가능수량) 조회
    TossSellableQuantity getSellableQuantity(Ticker ticker, Account account);
}
