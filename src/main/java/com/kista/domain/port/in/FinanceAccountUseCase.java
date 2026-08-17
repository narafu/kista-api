package com.kista.domain.port.in;

import com.kista.domain.model.finance.FinanceAccount;
import com.kista.domain.model.finance.FinanceAccountCommand;

import java.util.List;
import java.util.UUID;

public interface FinanceAccountUseCase {
    List<FinanceAccount> list(UUID userId, UUID requestedGroupId);
    FinanceAccount create(UUID userId, UUID requestedGroupId, FinanceAccountCommand command);
    FinanceAccount update(UUID accountId, UUID userId, FinanceAccountCommand command);
    void delete(UUID accountId, UUID userId);
}
