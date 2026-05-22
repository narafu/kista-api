package com.kista.domain.port.in;

import java.util.UUID;

public interface DeleteTradingCycleUseCase {
    void delete(UUID cycleId, UUID requesterId);
}
