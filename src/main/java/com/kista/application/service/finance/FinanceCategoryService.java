package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.model.finance.FinanceCategoryCommand;
import com.kista.domain.port.in.FinanceCategoryUseCase;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class FinanceCategoryService implements FinanceCategoryUseCase {

    private final FinanceCategoryPort categoryPort;
    private final FinanceGroupPort financeGroupPort;

    @Override
    @Transactional(readOnly = true)
    public List<FinanceCategory> list(UUID userId, UUID requestedGroupId, FinanceCategory.Type type) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        // 트리 중첩은 이 계층의 관심사가 아니다 — 도메인 레코드가 flat이라 web 계층 DTO에서 조립한다.
        return categoryPort.findSelectableByGroup(groupId, type).stream()
                .sorted(Comparator.comparingInt(FinanceCategory::sortOrder))
                .toList();
    }

    @Override
    public FinanceCategory create(UUID userId, UUID requestedGroupId, FinanceCategoryCommand command) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        resolveParent(command.parentId(), groupId, command.type());
        FinanceCategory category = new FinanceCategory(null, groupId, command.parentId(), userId,
                command.type(), command.name(), command.sortOrder(), null);
        FinanceCategory saved = categoryPort.save(category);
        log.info("카테고리 등록: groupId={}, categoryId={}", groupId, saved.id());
        return saved;
    }

    @Override
    public FinanceCategory update(UUID categoryId, UUID userId, FinanceCategoryCommand command) {
        FinanceCategory existing = categoryPort.findByIdOrThrow(categoryId);
        if (existing.isSystem()) {
            throw new SecurityException("시스템 카테고리는 수정할 수 없습니다");
        }
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        // type·parentId는 생성 후 불변 — 커맨드에 값이 실려와도 무시하고 기존 값을 유지한다.
        FinanceCategory updated = new FinanceCategory(existing.id(), existing.groupId(), existing.parentId(),
                existing.createdBy(), existing.type(), command.name(), command.sortOrder(), existing.createdAt());
        return categoryPort.save(updated);
    }

    @Override
    public void delete(UUID categoryId, UUID userId) {
        FinanceCategory existing = categoryPort.findByIdOrThrow(categoryId);
        if (existing.isSystem()) {
            throw new SecurityException("시스템 카테고리는 삭제할 수 없습니다");
        }
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        categoryPort.softDeleteWithChildren(categoryId);
        log.info("카테고리 삭제: categoryId={}, userId={}", categoryId, userId);
    }

    // V13 복합 FK(parent_id, group_id) 폐기의 유일한 대체 방어선.
    // 부모는 (같은 type) AND (시스템이거나 같은 그룹 소유)이어야 한다.
    private void resolveParent(UUID parentId, UUID groupId, FinanceCategory.Type type) {
        if (parentId == null) {
            return; // 신규 루트 — 허용
        }
        FinanceCategory parent = categoryPort.findByIdOrThrow(parentId);
        if (parent.type() != type) {
            throw new IllegalArgumentException("부모 카테고리와 타입이 일치해야 합니다");
        }
        if (!parent.isSystem() && !parent.groupId().equals(groupId)) {
            throw new IllegalArgumentException("다른 그룹의 카테고리를 부모로 지정할 수 없습니다");
        }
    }
}
