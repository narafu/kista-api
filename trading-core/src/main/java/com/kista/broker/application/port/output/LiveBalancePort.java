package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.BrokerAccountRef;
import com.kista.broker.domain.model.BrokerBalance;
import com.kista.sharedkernel.StrategyTicker;

// live 잔고 조회 — KIS/Toss 브로커 어댑터에서 구현
public interface LiveBalancePort {
    BrokerBalance getLiveBalance(BrokerAccountRef account, StrategyTicker ticker);
}
