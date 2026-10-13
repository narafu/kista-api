package com.kista.tradingcore.broker.application.port.output;

import com.kista.tradingcore.broker.domain.model.BrokerAccountRef;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.shared.kernel.StrategyTicker;

import java.time.LocalDate;
import java.util.List;

// 체결 내역 조회 — KIS: TTTS3035R / Toss: /api/v1/executions
public interface ExecutionPort {
    List<Execution> getExecutions(LocalDate from, LocalDate to, StrategyTicker ticker, BrokerAccountRef account);
}
