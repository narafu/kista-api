package com.kista.domain.port.in;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.util.UUID;

public interface RegisterStrategyUseCase {
    Strategy register(UUID userId, UUID accountId, Command command);

    record Command(
            Strategy.StrategyType type,
            Ticker ticker,      // null이면 전략 기본값
            BigDecimal multiple // null이면 1.0
    ) {}
}
