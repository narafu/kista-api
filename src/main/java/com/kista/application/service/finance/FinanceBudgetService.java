package com.kista.application.service.finance;

import com.kista.domain.model.finance.FinanceBudget;
import com.kista.domain.model.finance.FinanceBudgetCommand;
import com.kista.domain.model.finance.FinanceCategory;
import com.kista.domain.port.in.FinanceBudgetUseCase;
import com.kista.domain.port.out.FinanceBudgetPort;
import com.kista.domain.port.out.FinanceCategoryPort;
import com.kista.domain.port.out.FinanceGroupPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class FinanceBudgetService implements FinanceBudgetUseCase {

    private final FinanceBudgetPort budgetPort;
    private final FinanceGroupPort financeGroupPort;
    private final FinanceCategoryPort financeCategoryPort;

    @Override
    @Transactional(readOnly = true)
    public List<FinanceBudget> list(UUID userId, UUID requestedGroupId, UUID categoryId, LocalDate date) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return budgetPort.findMyScope(userId, currentGroupId, categoryId, date);
    }

    // 신규 등록은 항상 개인 소유로 저장한다 — requestedGroupId는 무시(그룹 공유는 shareToGroup으로 별도 전환).
    @Override
    public FinanceBudget create(UUID userId, UUID requestedGroupId, FinanceBudgetCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        verifyBudgetCommand(userId, currentGroupId, command);
        FinanceBudget budget = new FinanceBudget(null, null, command.categoryId(), userId,
                command.applyStartDate(), command.applyEndDate(), command.amount(), null);
        // 기간 중첩 시 어댑터가 finance_budgets_no_overlap EXCLUDE 위반을 OverlappingPeriodException으로 변환한다.
        FinanceBudget saved = budgetPort.save(budget);
        log.info("예산 등록: userId={}, budgetId={}", userId, saved.id());
        return saved;
    }

    @Override
    public FinanceBudget update(UUID budgetId, UUID userId, FinanceBudgetCommand command) {
        FinanceBudget existing = budgetPort.findByIdOrThrow(budgetId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        verifyBudgetCommand(userId, currentGroupId, command);
        FinanceBudget updated = new FinanceBudget(existing.id(), existing.groupId(), command.categoryId(),
                existing.userId(), command.applyStartDate(), command.applyEndDate(), command.amount(), existing.createdAt());
        return budgetPort.save(updated);
    }

    // 카테고리가 실제로 접근 가능하고(시스템이거나 본인/내 그룹 소유) 부호 없는 자산 스냅샷 전용 타입은 아닌지,
    // 그리고 종료일이 시작일보다 앞서지 않는지를 DB 제약에 닿기 전에 미리 걸러낸다. 이렇게 해두면
    // FinanceBudgetPersistenceAdapter.save()의 catch(DataIntegrityViolationException)에 도달하는 위반은
    // 사실상 finance_budgets_no_overlap 하나만 남아, 그 catch가 무엇이든 OverlappingPeriodException으로
    // 오라벨링하는 문제(다른 위반까지 "기간 중첩"으로 잘못 보고하는 문제)가 실질적으로 발생하지 않는다.
    private void verifyBudgetCommand(UUID userId, UUID currentGroupId, FinanceBudgetCommand command) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(command.categoryId());
        category.verifyAccessibleBy(userId, currentGroupId);
        if (category.type() == FinanceCategory.Type.ASSET) {
            throw new IllegalArgumentException("자산 카테고리에는 예산을 걸 수 없습니다");
        }
        if (command.applyEndDate() != null && command.applyEndDate().isBefore(command.applyStartDate())) {
            throw new IllegalArgumentException("적용 종료일은 시작일보다 앞설 수 없습니다");
        }
    }

    @Override
    public void delete(UUID budgetId, UUID userId) {
        FinanceBudget existing = budgetPort.findByIdOrThrow(budgetId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        // 파생 설정이므로 하드 삭제 (소프트 삭제 컬럼 없음)
        budgetPort.delete(budgetId);
        log.info("예산 삭제: budgetId={}, userId={}", budgetId, userId);
    }

    // 개인 소유 예산을 소유자가 자신의 현재 그룹으로 전환한다. 본인 것만 전환 가능(그룹 멤버 전체 아님).
    @Override
    public FinanceBudget shareToGroup(UUID budgetId, UUID userId) {
        FinanceBudget existing = budgetPort.findByIdOrThrow(budgetId);
        if (!existing.userId().equals(userId)) {
            throw new SecurityException("본인 소유 예산만 그룹에 공유할 수 있습니다");
        }
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId)
                .orElseThrow(() -> new IllegalStateException("소속된 그룹이 없습니다"));
        if (currentGroupId.equals(existing.groupId())) {
            return existing; // 이미 같은 그룹에 공유된 상태 — 멱등
        }
        if (existing.groupId() != null) {
            throw new IllegalStateException("이미 다른 그룹에 공유된 항목입니다");
        }
        FinanceBudget shared = new FinanceBudget(existing.id(), currentGroupId, existing.categoryId(),
                existing.userId(), existing.applyStartDate(), existing.applyEndDate(), existing.amount(), existing.createdAt());
        // 그룹 전환 시 겹침(EXCLUDE 제약)이 있으면 어댑터가 OverlappingPeriodException으로 변환하므로 그대로 전파.
        FinanceBudget saved = budgetPort.save(shared);
        log.info("예산 그룹 공유 전환: budgetId={}, groupId={}", budgetId, currentGroupId);
        return saved;
    }

    // 그룹 공유 예산을 개인 소유로 되돌린다. 소유자는 그대로 유지, groupId만 null로.
    @Override
    public FinanceBudget unshare(UUID budgetId, UUID userId) {
        FinanceBudget existing = budgetPort.findByIdOrThrow(budgetId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        existing.verifyAccessibleBy(userId, currentGroupId);
        if (existing.groupId() == null) {
            return existing; // 이미 개인 소유 — 멱등
        }
        FinanceBudget personal = new FinanceBudget(existing.id(), null, existing.categoryId(),
                existing.userId(), existing.applyStartDate(), existing.applyEndDate(), existing.amount(), existing.createdAt());
        // 개인 소유로 되돌릴 때도 겹침(EXCLUDE 제약)이 있으면 어댑터가 OverlappingPeriodException으로 변환하므로 그대로 전파.
        FinanceBudget saved = budgetPort.save(personal);
        log.info("예산 그룹 공유 해제: budgetId={}, userId={}", budgetId, userId);
        return saved;
    }
}
