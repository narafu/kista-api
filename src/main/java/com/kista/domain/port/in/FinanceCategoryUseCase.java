package com.kista.domain.port.in;

import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.model.finance.FinanceCategoryCommand;

import java.util.List;
import java.util.UUID;

public interface FinanceCategoryUseCase {
    // requestedGroupId null이면 개인 그룹. type null이면 전체 타입. L1에 children이 중첩된 트리로 반환.
    List<FinanceCategory> list(UUID userId, UUID requestedGroupId, FinanceCategory.Type type);
    FinanceCategory create(UUID userId, UUID requestedGroupId, FinanceCategoryCommand command);
    FinanceCategory update(UUID categoryId, UUID userId, FinanceCategoryCommand command);
    void delete(UUID categoryId, UUID userId);
}
