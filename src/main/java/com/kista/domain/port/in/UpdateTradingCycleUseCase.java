package com.kista.domain.port.in;

import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

import java.math.BigDecimal;
import java.util.UUID;

public interface UpdateTradingCycleUseCase {
    TradingCycle update(UUID cycleId, UUID requesterId, Command command);

    record Command(
            Ticker ticker,      // null이면 기존값 유지
            BigDecimal multiple // null이면 기존값 유지
    ) {}
}
