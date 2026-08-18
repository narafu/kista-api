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
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        return budgetPort.findByGroupId(groupId, categoryId, date);
    }

    @Override
    public FinanceBudget create(UUID userId, UUID requestedGroupId, FinanceBudgetCommand command) {
        UUID groupId = financeGroupPort.resolveGroupId(userId, requestedGroupId);
        verifyBudgetCommand(groupId, command);
        FinanceBudget budget = new FinanceBudget(null, groupId, command.categoryId(), userId,
                command.applyStartDate(), command.applyEndDate(), command.amount(), null);
        // 기간 중첩 시 어댑터가 finance_budgets_no_overlap EXCLUDE 위반을 OverlappingPeriodException으로 변환한다.
        FinanceBudget saved = budgetPort.save(budget);
        log.info("예산 등록: groupId={}, budgetId={}", groupId, saved.id());
        return saved;
    }

    @Override
    public FinanceBudget update(UUID budgetId, UUID userId, FinanceBudgetCommand command) {
        FinanceBudget existing = budgetPort.findByIdOrThrow(budgetId);
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        verifyBudgetCommand(existing.groupId(), command);
        FinanceBudget updated = new FinanceBudget(existing.id(), existing.groupId(), command.categoryId(),
                existing.createdBy(), command.applyStartDate(), command.applyEndDate(), command.amount(), existing.createdAt());
        return budgetPort.save(updated);
    }

    // 카테고리가 이 그룹에서 실제로 접근 가능한지(시스템이거나 같은 그룹)·부호 없는 자산 스냅샷 전용 타입은
    // 아니는지, 그리고 종료일이 시작일보다 앞서지 않는지를 DB 제약에 닿기 전에 미리 걸러낸다. 이렇게 해두면
    // FinanceBudgetPersistenceAdapter.save()의 catch(DataIntegrityViolationException)에 도달하는 위반은
    // 사실상 finance_budgets_no_overlap 하나만 남아, 그 catch가 무엇이든 OverlappingPeriodException으로
    // 오라벨링하는 문제(다른 위반까지 "기간 중첩"으로 잘못 보고하는 문제)가 실질적으로 발생하지 않는다.
    private void verifyBudgetCommand(UUID groupId, FinanceBudgetCommand command) {
        FinanceCategory category = financeCategoryPort.findByIdOrThrow(command.categoryId());
        category.verifyOwnedBy(groupId);
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
        financeGroupPort.resolveGroupId(userId, existing.groupId());
        // 파생 설정이므로 하드 삭제 (소프트 삭제 컬럼 없음)
        budgetPort.delete(budgetId);
        log.info("예산 삭제: budgetId={}, userId={}", budgetId, userId);
    }
}
