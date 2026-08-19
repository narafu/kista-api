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
        // findByIdOrThrow(삭제된 카테고리도 조회됨)를 쓰면 안 됨 — FinanceCategory에 deletedAt이 없어
        // save() merge 시 삭제 상태가 조용히 풀려버린다(코드리뷰에서 발견, 2026-08-19, FinanceAccountService와 동일 결함).
        FinanceCategory existing = categoryPort.findActiveByIdOrThrow(categoryId);
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

    @Override
    @Transactional(readOnly = true)
    public List<FinanceCategory> listSystem(FinanceCategory.Type type) {
        // findSelectableByGroup(null, type)의 네이티브 쿼리는 "(group_id IS NULL OR group_id = :groupId)" —
        // :groupId가 null로 바인딩되면 두 번째 조건은 SQL 3치 논리상 항상 NULL(=false)이라
        // group_id IS NULL 하나만 남는다. 즉 시스템 카테고리만 정확히 걸러진다.
        return categoryPort.findSelectableByGroup(null, type).stream()
                .sorted(Comparator.comparingInt(FinanceCategory::sortOrder))
                .toList();
    }

    @Override
    public FinanceCategory createSystem(FinanceCategoryCommand command) {
        resolveParent(command.parentId(), null, command.type());
        FinanceCategory category = new FinanceCategory(null, null, command.parentId(), null,
                command.type(), command.name(), command.sortOrder(), null);
        FinanceCategory saved = categoryPort.save(category);
        log.info("시스템 카테고리 등록: categoryId={}", saved.id());
        return saved;
    }

    @Override
    public FinanceCategory updateSystem(UUID categoryId, FinanceCategoryCommand command) {
        // requireSystem()이 아닌 삭제된 카테고리를 제외하는 조회 사용 — update()와 동일한 부활 방지 이유
        FinanceCategory existing = requireSystem(categoryPort.findActiveByIdOrThrow(categoryId));
        // type·parentId는 생성 후 불변 — update()와 동일한 정책.
        FinanceCategory updated = new FinanceCategory(existing.id(), null, existing.parentId(),
                null, existing.type(), command.name(), command.sortOrder(), existing.createdAt());
        return categoryPort.save(updated);
    }

    @Override
    public void deleteSystem(UUID categoryId) {
        requireSystem(categoryId);
        categoryPort.softDeleteWithChildren(categoryId);
        log.info("시스템 카테고리 삭제: categoryId={}", categoryId);
    }

    // 대상이 시스템 카테고리가 아니면(즉 그룹 카테고리 id를 잘못 넘기면) 400으로 거부 — update()/delete()의
    // "시스템이면 거부"와 정반대 방향의 가드. deleteSystem()은 이미 삭제된 카테고리를 다시 삭제해도
    // 멱등하게 무해하므로 findByIdOrThrow(삭제 카테고리도 조회됨) 그대로 사용한다.
    private FinanceCategory requireSystem(UUID categoryId) {
        return requireSystem(categoryPort.findByIdOrThrow(categoryId));
    }

    private FinanceCategory requireSystem(FinanceCategory existing) {
        if (!existing.isSystem()) {
            throw new IllegalArgumentException("시스템 카테고리가 아닙니다: " + existing.id());
        }
        return existing;
    }

    // V13 복합 FK(parent_id, group_id) 폐기의 유일한 대체 방어선.
    // 부모는 (같은 type) AND (시스템이거나 같은 그룹 소유)이어야 한다.
    // groupId=null(시스템 카테고리 생성)로 호출하면 parent.groupId().equals(null)이 항상 false이므로
    // 시스템이 아닌 부모는 자동으로 거부된다 — 시스템 자식은 시스템 부모만 가질 수 있다는 정책이 그대로 성립.
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
