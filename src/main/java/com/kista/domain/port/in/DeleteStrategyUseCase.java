package com.kista.domain.port.in;

import java.util.UUID;

public interface DeleteStrategyUseCase {
    void delete(UUID strategyId, UUID requesterId);
}
