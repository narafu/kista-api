package com.kista.domain.port.out;

import com.kista.domain.model.finance.FinanceBudget;

import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public interface FinanceBudgetPort {
    // categoryId/date는 선택적 필터 — null이면 무시. date 지정 시 그 날짜에 유효한(기간 포함) 예산만.
    // currentGroupId는 무그룹 유저면 null(개인 예산만 조회).
    List<FinanceBudget> findMyScope(UUID userId, UUID currentGroupId, UUID categoryId, LocalDate date);

    // 개인 스코프(group_id IS NULL, userId 소유) + 동일 categoryId 중 [startDate, endDate]와 겹치는 예산 조회.
    // endDate=null이면 무기한 새 예산 — 시작일 이후 전부와 겹침 대상.
    List<FinanceBudget> findOverlapping(UUID userId, UUID categoryId, LocalDate startDate, LocalDate endDate);

    Optional<FinanceBudget> findById(UUID id);

    default FinanceBudget findByIdOrThrow(UUID id) {
        return findById(id).orElseThrow(
                () -> new NoSuchElementException("예산을 찾을 수 없습니다: " + id));
    }

    // finance_budgets_no_overlap 위반 시 FinanceBudget.OverlappingPeriodException으로 변환 (saveAndFlush 필수)
    FinanceBudget save(FinanceBudget budget);

    void delete(UUID id); // 파생 설정이므로 하드 삭제
    void deleteByUserId(UUID userId); // 회원 탈퇴 시 하드 삭제
}
