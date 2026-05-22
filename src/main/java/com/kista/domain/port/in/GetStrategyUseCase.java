package com.kista.domain.port.in;

import com.kista.domain.model.strategy.Strategy;

import java.util.List;
import java.util.UUID;

public interface GetStrategyUseCase {
    List<Strategy> listByAccountId(UUID accountId, UUID requesterId);
    Strategy getById(UUID strategyId, UUID requesterId);
}
