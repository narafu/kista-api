package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.RegisterCycleCommand;
import com.kista.domain.model.tradingcycle.TradingCycle;

import java.util.UUID;

public interface RegisterTradingCycleUseCase {
    TradingCycle register(UUID userId, UUID accountId, RegisterCycleCommand command);
}
