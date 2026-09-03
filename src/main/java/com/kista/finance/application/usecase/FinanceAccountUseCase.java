package com.kista.finance.application.usecase;

import com.kista.finance.domain.model.FinanceAccount;
import com.kista.finance.domain.model.FinanceAccountCommand;

import java.util.List;
import java.util.UUID;

public interface FinanceAccountUseCase {
    List<FinanceAccount> list(UUID userId, UUID requestedGroupId);
    FinanceAccount create(UUID userId, UUID requestedGroupId, FinanceAccountCommand command);
    FinanceAccount update(UUID accountId, UUID userId, FinanceAccountCommand command);
    void delete(UUID accountId, UUID userId);
    FinanceAccount shareToGroup(UUID accountId, UUID userId);
    FinanceAccount unshare(UUID accountId, UUID userId);
}
