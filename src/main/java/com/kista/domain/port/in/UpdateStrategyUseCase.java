package com.kista.domain.port.in;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.util.UUID;

public interface UpdateStrategyUseCase {
    Strategy update(UUID strategyId, UUID requesterId, Command command);

    record Command(
            Ticker ticker,      // null이면 기존값 유지
            BigDecimal multiple // null이면 기존값 유지
    ) {}
}
