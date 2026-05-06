package com.kista.domain.port.in;

import java.util.UUID;

public interface PauseStrategyUseCase {
    void pause(UUID accountId, UUID requesterId);
}
