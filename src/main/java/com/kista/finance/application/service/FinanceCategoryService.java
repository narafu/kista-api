package com.kista.finance.application.service;

import com.kista.finance.domain.model.FinanceCategory;
import com.kista.finance.domain.model.FinanceCategoryCommand;
import com.kista.finance.domain.port.in.FinanceCategoryUseCase;
import com.kista.finance.domain.port.out.FinanceCategoryPort;
import com.kista.finance.domain.port.out.FinanceGroupPort;
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
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        // 트리 중첩은 이 계층의 관심사가 아니다 — 도메인 레코드가 flat이라 web 계층 DTO에서 조립한다.
        return categoryPort.findSelectable(userId, currentGroupId, type).stream()
                .sorted(Comparator.comparingInt(FinanceCategory::sortOrder))
                .toList();
    }

    // 신규 등록은 항상 개인 소유로 저장한다 — requestedGroupId는 무시.
    @Override
    public FinanceCategory create(UUID userId, UUID requestedGroupId, FinanceCategoryCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        resolveParent(command.parentId(), userId, currentGroupId, command.type());
        FinanceCategory category = new FinanceCategory(null, null, command.parentId(), userId,
                command.type(), command.name(), command.sortOrder(), null);
        FinanceCategory saved = categoryPort.save(category);
        log.info("카테고리 등록: userId={}, categoryId={}", userId, saved.id());
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
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        // type·parentId는 생성 후 불변 — 커맨드에 값이 실려와도 무시하고 기존 값을 유지한다.
        FinanceCategory updated = new FinanceCategory(existing.id(), existing.groupId(), existing.parentId(),
                existing.userId(), existing.type(), command.name(), command.sortOrder(), existing.createdAt());
        return categoryPort.save(updated);
    }

    // 개인 소유 카테고리를 소유자가 자신의 현재 그룹으로 전환한다. 하위 카테고리(임의 depth)도 함께 전환된다.
    // 개인 카테고리의 자식은 resolveParent()가 부모 접근권한을 요구해 구조적으로 항상 같은 소유자 트리이므로
    // 벌크 UPDATE에 개별 userId 재검증이 필요 없다.
    @Override
    public FinanceCategory shareToGroup(UUID categoryId, UUID userId) {
        FinanceCategory existing = categoryPort.findActiveByIdOrThrow(categoryId);
        if (existing.isSystem()) {
            throw new SecurityException("시스템 카테고리는 공유할 수 없습니다");
        }
        return GroupShareSupport.shareToGroup(existing, userId, financeGroupPort.findCurrentGroupId(userId),
                        "본인 소유 카테고리만 그룹에 공유할 수 있습니다")
                .map(shared -> {
                    categoryPort.shareToGroupWithChildren(categoryId, shared.groupId());
                    log.info("카테고리 그룹 공유 전환(하위 포함): categoryId={}, groupId={}", categoryId, shared.groupId());
                    return categoryPort.findByIdOrThrow(categoryId);
                })
                .orElse(existing);
    }

    // 그룹 공유 카테고리를 개인 소유로 되돌린다. 하위 카테고리(임의 depth)도 함께 되돌아간다.
    @Override
    public FinanceCategory unshare(UUID categoryId, UUID userId) {
        FinanceCategory existing = categoryPort.findActiveByIdOrThrow(categoryId);
        if (existing.isSystem()) {
            throw new SecurityException("시스템 카테고리는 공유 해제할 수 없습니다");
        }
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return GroupShareSupport.unshare(existing, userId, currentGroupId)
                .map(personal -> {
                    categoryPort.unshareWithChildren(categoryId);
                    log.info("카테고리 그룹 공유 해제(하위 포함): categoryId={}, userId={}", categoryId, userId);
                    return categoryPort.findByIdOrThrow(categoryId);
                })
                .orElse(existing);
    }

    @Override
    public void delete(UUID categoryId, UUID userId) {
        FinanceCategory existing = categoryPort.findByIdOrThrow(categoryId);
        if (existing.isSystem()) {
            throw new SecurityException("시스템 카테고리는 삭제할 수 없습니다");
        }
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        categoryPort.softDeleteWithChildren(categoryId);
        log.info("카테고리 삭제: categoryId={}, userId={}", categoryId, userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinanceCategory> listSystem(FinanceCategory.Type type) {
        // findSelectable(null, null, type)은 (userId IS NULL AND groupId IS NULL) 조건으로 시스템만 정확히 걸러진다.
        return categoryPort.findSelectable(null, null, type).stream()
                .sorted(Comparator.comparingInt(FinanceCategory::sortOrder))
                .toList();
    }

    @Override
    public FinanceCategory createSystem(FinanceCategoryCommand command) {
        resolveParent(command.parentId(), null, null, command.type());
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
    // 부모는 (같은 type) AND (시스템이거나 접근 가능한 개인/그룹 카테고리)이어야 한다.
    // verifyAccessibleBy가 던지는 SecurityException을 IllegalArgumentException(400)으로 재던진다 —
    // "권한 없음"이 아니라 "잘못된 부모 지정"으로 취급하는 게 이 API의 기존 계약이었다.
    private void resolveParent(UUID parentId, UUID userId, UUID currentGroupId, FinanceCategory.Type type) {
        if (parentId == null) {
            return; // 신규 루트 — 허용
        }
        FinanceCategory parent = categoryPort.findByIdOrThrow(parentId);
        if (parent.type() != type) {
            throw new IllegalArgumentException("부모 카테고리와 타입이 일치해야 합니다");
        }
        try {
            parent.verifyAccessibleBy(userId, currentGroupId);
        } catch (SecurityException e) {
            throw new IllegalArgumentException("다른 그룹의 카테고리를 부모로 지정할 수 없습니다");
        }
    }
}
