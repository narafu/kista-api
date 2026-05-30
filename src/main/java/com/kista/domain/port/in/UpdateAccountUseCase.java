package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;

import java.util.UUID;

public interface UpdateAccountUseCase {
    Account update(UUID accountId, UUID requesterId, Command command);

    record Command(
            String nickname
    ) {}
}
