package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;

public interface TosAccountPort {
    // GET /api/v1/holdings — 보유 주식 잔고 조회
    AccountBalance getBalance(Account account, Ticker ticker);
}
