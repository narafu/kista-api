package com.kista.finance.application.usecase;

import com.kista.finance.domain.model.FinanceTransaction;
import com.kista.finance.domain.model.FinanceTransactionCommand;

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

    // 그룹 공유 거래내역을 개인 소유로 되돌린다. 같은 그룹 멤버면 누구든 가능(소유자 한정 아님).
    FinanceTransaction unshare(UUID transactionId, UUID userId);
}
