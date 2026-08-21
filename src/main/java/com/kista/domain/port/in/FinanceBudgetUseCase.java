package com.kista.domain.port.in;

import com.kista.domain.model.finance.FinanceBudget;
import com.kista.domain.model.finance.FinanceBudgetCommand;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface FinanceBudgetUseCase {
    List<FinanceBudget> list(UUID userId, UUID requestedGroupId, UUID categoryId, LocalDate date);
    FinanceBudget create(UUID userId, UUID requestedGroupId, FinanceBudgetCommand command);
    FinanceBudget update(UUID budgetId, UUID userId, FinanceBudgetCommand command);
    void delete(UUID budgetId, UUID userId);

    // 개인 소유 예산을 소유자의 현재 그룹으로 공유 전환한다.
    FinanceBudget shareToGroup(UUID budgetId, UUID userId);
}
