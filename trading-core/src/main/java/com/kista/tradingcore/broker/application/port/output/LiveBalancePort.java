package com.kista.tradingcore.broker.application.port.output;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.BrokerBalance;
import com.kista.shared.kernel.StrategyTicker;

// live 잔고 조회 — KIS/Toss 브로커 어댑터에서 구현
public interface LiveBalancePort {
    BrokerBalance getLiveBalance(BrokerAccountRef account, StrategyTicker ticker);
}
