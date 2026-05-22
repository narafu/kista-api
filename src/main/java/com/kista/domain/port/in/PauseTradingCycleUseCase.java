package com.kista.domain.port.in;

import java.util.UUID;

public interface PauseTradingCycleUseCase {
    void pause(UUID cycleId, UUID requesterId);
}
