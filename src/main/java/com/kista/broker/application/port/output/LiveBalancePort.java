package com.kista.broker.application.port.output;

import com.kista.domain.model.account.Account;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.domain.model.strategy.Strategy.Ticker;

// live 잔고 조회 — KIS/Toss 브로커 어댑터에서 구현
public interface LiveBalancePort {
    BrokerBalance getLiveBalance(Account account, Ticker ticker);
}
