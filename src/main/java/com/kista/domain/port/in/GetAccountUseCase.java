package com.kista.domain.port.in;

import com.kista.domain.model.Account;

import java.util.List;
import java.util.UUID;

public interface GetAccountUseCase {
    List<Account> listByUser(UUID userId);
    Account getById(UUID id);
}
