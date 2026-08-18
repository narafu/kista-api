package com.kista.domain.port.out;

import com.kista.domain.model.finance.FinanceBudget;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface FinanceBudgetPort {
    // categoryId/date는 선택적 필터 — null이면 무시. date 지정 시 그 날짜에 유효한(기간 포함) 예산만.
    List<FinanceBudget> findByGroupId(UUID groupId, UUID categoryId, LocalDate date);

    Optional<FinanceBudget> findById(UUID id);

    default FinanceBudget findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("예산을 찾을 수 없습니다: " + id));
    }

    // finance_budgets_no_overlap 위반 시 FinanceBudget.OverlappingPeriodException으로 변환 (saveAndFlush 필수)
    FinanceBudget save(FinanceBudget budget);

    void delete(UUID id); // 파생 설정이므로 하드 삭제
    void deleteByCreatedBy(UUID userId); // 회원 탈퇴 시 하드 삭제
    void reassignGroup(UUID fromGroupId, UUID toGroupId, UUID createdBy); // 그룹 이탈 시 개인 그룹으로 이관
}
