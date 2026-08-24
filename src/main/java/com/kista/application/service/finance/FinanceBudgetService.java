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
import java.util.ArrayList;
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
    // 등록 전, 같은 카테고리·개인 스코프에서 겹치는 기존 예산을 규칙에 따라 자동 트림/삭제한다.
    // 규칙으로 판단 불가한 겹침(중간에 끼거나 새 예산 종료일 뒤로 이어짐)은 409로 거부하며,
    // 거부 시 어떤 후보도 변경하지 않는다(전량 판정 후 실행) — resolveOverlapActions() 참고.
    @Override
    public FinanceBudget create(UUID userId, UUID requestedGroupId, FinanceBudgetCommand command) {
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        verifyBudgetCommand(userId, currentGroupId, command);

        List<FinanceBudget> candidates = budgetPort.findOverlapping(
                userId, command.categoryId(), command.applyStartDate(), command.applyEndDate());
        List<OverlapAction> actions = resolveOverlapActions(candidates, command);
        actions.forEach(action -> action.execute(budgetPort));

        FinanceBudget budget = new FinanceBudget(null, null, command.categoryId(), userId,
                command.applyStartDate(), command.applyEndDate(), command.amount(), null);
        // 기간 중첩 시 어댑터가 finance_budgets_no_overlap EXCLUDE 위반을 OverlappingPeriodException으로 변환한다.
        FinanceBudget saved = budgetPort.save(budget);
        log.info("예산 등록: userId={}, budgetId={}", userId, saved.id());
        return saved;
    }

    // 겹치는 후보 각각을 트림/삭제/거부로 판정한다. 하나라도 거부 조건이면 즉시 예외를 던져
    // create()가 어떤 DB 변경도 실행하지 않도록 한다(부분 적용 방지).
    private List<OverlapAction> resolveOverlapActions(List<FinanceBudget> candidates, FinanceBudgetCommand command) {
        LocalDate newStart = command.applyStartDate();
        LocalDate newEnd = command.applyEndDate();
        List<OverlapAction> actions = new ArrayList<>();
        for (FinanceBudget existing : candidates) {
            LocalDate existingStart = existing.applyStartDate();
            LocalDate existingEnd = existing.applyEndDate();
            if (existingStart.isBefore(newStart)) {
                boolean fitsWithinNewEnd = existingEnd == null || newEnd == null || !existingEnd.isAfter(newEnd);
                if (!fitsWithinNewEnd) {
                    throw new FinanceBudget.OverlappingPeriodException("해당 기간에 자동조정할 수 없는 기존 예산이 있습니다");
                }
                actions.add(OverlapAction.trim(existing, newStart.minusDays(1)));
            } else {
                boolean fullyWithinNewRange = newEnd == null || (existingEnd != null && !existingEnd.isAfter(newEnd));
                if (!fullyWithinNewRange) {
                    throw new FinanceBudget.OverlappingPeriodException("해당 기간에 자동조정할 수 없는 기존 예산이 있습니다");
                }
                actions.add(OverlapAction.delete(existing));
            }
        }
        return actions;
    }

    // 겹침 판정 결과 하나(트림 또는 삭제)를 표현하는 내부 값 객체. execute()가 실제 포트 호출을 수행한다.
    private record OverlapAction(FinanceBudget existing, LocalDate trimmedEndDate) {
        static OverlapAction trim(FinanceBudget existing, LocalDate trimmedEndDate) {
            return new OverlapAction(existing, trimmedEndDate);
        }

        static OverlapAction delete(FinanceBudget existing) {
            return new OverlapAction(existing, null);
        }

        void execute(FinanceBudgetPort budgetPort) {
            if (trimmedEndDate != null) {
                FinanceBudget trimmed = new FinanceBudget(existing.id(), existing.groupId(), existing.categoryId(),
                        existing.userId(), existing.applyStartDate(), trimmedEndDate, existing.amount(), existing.createdAt());
                budgetPort.save(trimmed);
            } else {
                budgetPort.delete(existing.id());
            }
        }
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
    // 그룹 전환/복귀 시 겹침(EXCLUDE 제약)이 있으면 어댑터가 OverlappingPeriodException으로 변환하므로 그대로 전파.
    @Override
    public FinanceBudget shareToGroup(UUID budgetId, UUID userId) {
        FinanceBudget existing = budgetPort.findByIdOrThrow(budgetId);
        return GroupShareSupport.shareToGroup(existing, userId, financeGroupPort.findCurrentGroupId(userId),
                        "본인 소유 예산만 그룹에 공유할 수 있습니다")
                .map(shared -> {
                    FinanceBudget saved = budgetPort.save(shared);
                    log.info("예산 그룹 공유 전환: budgetId={}, groupId={}", budgetId, saved.groupId());
                    return saved;
                })
                .orElse(existing);
    }

    // 그룹 공유 예산을 개인 소유로 되돌린다. 소유자는 그대로 유지, groupId만 null로.
    @Override
    public FinanceBudget unshare(UUID budgetId, UUID userId) {
        FinanceBudget existing = budgetPort.findByIdOrThrow(budgetId);
        UUID currentGroupId = financeGroupPort.findCurrentGroupId(userId).orElse(null);
        return GroupShareSupport.unshare(existing, userId, currentGroupId)
                .map(personal -> {
                    FinanceBudget saved = budgetPort.save(personal);
                    log.info("예산 그룹 공유 해제: budgetId={}, userId={}", budgetId, userId);
                    return saved;
                })
                .orElse(existing);
    }
}
