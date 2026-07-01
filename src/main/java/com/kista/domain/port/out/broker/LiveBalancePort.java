package com.kista.domain.port.out.broker;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;

// live 잔고 조회 — KIS: KisAccountPort 위임 / Toss: TosAccountPort 위임
public interface LiveBalancePort {
    AccountBalance getLiveBalance(Account account, Ticker ticker);
}
