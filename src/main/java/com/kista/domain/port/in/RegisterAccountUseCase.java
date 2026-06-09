package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.RegisterAccountCommand;

import java.util.UUID;

public interface RegisterAccountUseCase {
    Account register(UUID userId, RegisterAccountCommand command);
}
