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

    // 개인 소유 거래내역을 소유자의 현재 그룹으로 공유 전환한다.
    FinanceTransaction shareToGroup(UUID transactionId, UUID userId);
}
