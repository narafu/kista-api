package com.kista.domain.port.in;

import java.util.UUID;

public interface ResumeTradingCycleUseCase {
    void resume(UUID cycleId, UUID requesterId);
}
