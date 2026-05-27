package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.TradingCycle;

import java.util.UUID;

public interface UpdateTradingCycleUseCase {
    TradingCycle update(UUID cycleId, UUID requesterId, Command cmd);

    record Command(
            TradingCycle.CycleSeedType cycleSeedType // null이면 기존 값 유지
    ) {}
}
