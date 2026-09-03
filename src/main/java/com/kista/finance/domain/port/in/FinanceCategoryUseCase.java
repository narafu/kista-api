package com.kista.finance.domain.port.in;

import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.model.FinanceCategoryCommand;

import java.util.List;
import java.util.UUID;

public interface FinanceCategoryUseCase {
    // requestedGroupId null이면 개인 그룹. type null이면 전체 타입. flat 목록 반환 — 트리 조립은 web 계층 책임.
    List<FinanceCategory> list(UUID userId, UUID requestedGroupId, FinanceCategory.Type type);
    FinanceCategory create(UUID userId, UUID requestedGroupId, FinanceCategoryCommand command);
    FinanceCategory update(UUID categoryId, UUID userId, FinanceCategoryCommand command);
    // 하위 카테고리(임의 depth)도 함께 groupId 전환 — 개인 소유자만 가능
    FinanceCategory shareToGroup(UUID categoryId, UUID userId);
    // 하위 카테고리(임의 depth)도 함께 groupId 해제 — 같은 그룹 멤버면 누구든 가능
    FinanceCategory unshare(UUID categoryId, UUID userId);
    void delete(UUID categoryId, UUID userId);

    // 시스템(그룹 공용) 카테고리 admin 관리 — /api/admin/finance/categories 전용, 그룹 개념이 없다.
    // type null이면 전체 타입. flat 목록 반환 — 트리 조립은 web 계층 책임.
    List<FinanceCategory> listSystem(FinanceCategory.Type type);
    FinanceCategory createSystem(FinanceCategoryCommand command);
    FinanceCategory updateSystem(UUID categoryId, FinanceCategoryCommand command);
    void deleteSystem(UUID categoryId);
}
