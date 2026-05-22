package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.TradingCycle;

import java.util.List;
import java.util.UUID;

public interface GetTradingCycleUseCase {
    List<TradingCycle> listByAccountId(UUID accountId, UUID requesterId);
    TradingCycle getById(UUID cycleId, UUID requesterId);
}
