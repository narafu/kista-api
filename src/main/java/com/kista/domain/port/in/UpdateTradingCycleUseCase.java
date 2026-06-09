package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.UpdateCycleCommand;

import java.util.UUID;

public interface UpdateTradingCycleUseCase {
    TradingCycle update(UUID cycleId, UUID requesterId, UpdateCycleCommand cmd);
}
