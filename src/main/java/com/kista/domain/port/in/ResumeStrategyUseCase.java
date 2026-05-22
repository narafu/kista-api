package com.kista.domain.port.in;

import java.util.UUID;

public interface ResumeStrategyUseCase {
    void resume(UUID strategyId, UUID requesterId);
}
