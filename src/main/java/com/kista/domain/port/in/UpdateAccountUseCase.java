package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.account.UpdateAccountCommand;

import java.util.UUID;

public interface UpdateAccountUseCase {
    Account update(UUID accountId, UUID requesterId, UpdateAccountCommand command);
}
