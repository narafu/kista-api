package com.kista.application.usecase;

import java.util.UUID;

public interface UpdateBalanceCheckUseCase {
    void update(UpdateBalanceCheckCommand command);

    record UpdateBalanceCheckCommand(UUID userId, boolean enabled) {}
}
