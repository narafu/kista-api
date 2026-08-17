package com.kista.domain.port.in;

import com.kista.domain.model.finance.FinanceTransaction;
import com.kista.domain.model.finance.FinanceTransactionCommand;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FinanceTransactionUseCase {
    List<FinanceTransaction> list(UUID userId, UUID requestedGroupId, LocalDate from, LocalDate to, UUID categoryId, UUID createdBy);
    FinanceTransaction create(UUID userId, UUID requestedGroupId, FinanceTransactionCommand command);
    FinanceTransaction update(UUID transactionId, UUID userId, FinanceTransactionCommand command);
    void delete(UUID transactionId, UUID userId);
}
