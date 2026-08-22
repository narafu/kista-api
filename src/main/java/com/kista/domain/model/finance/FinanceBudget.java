package com.kista.domain.model.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FinanceBudget(
        UUID id,               // PK
        UUID groupId,          // FK → finance_groups.id, null이면 개인 예산
        UUID categoryId,       // FK → finance_categories.id
        UUID userId,           // FK → users.id, 소유자
        LocalDate applyStartDate,
        LocalDate applyEndDate, // null이면 무기한
        long amount,            // 월 할당 예산, 원화 정수
        Instant createdAt       // DB created_at, 신규 등록 시 null
) implements GroupShareable<FinanceBudget> {
    // 접근 불가 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyAccessibleBy(UUID requesterUserId, UUID requesterGroupId) {
        boolean owned = userId.equals(requesterUserId);
        boolean sharedInMyGroup = groupId != null && groupId.equals(requesterGroupId);
        if (!owned && !sharedInMyGroup) {
            throw new SecurityException("예산에 대한 접근 권한이 없습니다");
        }
    }

    @Override
    public FinanceBudget withGroupId(UUID groupId) {
        return new FinanceBudget(id, groupId, categoryId, userId, applyStartDate, applyEndDate, amount, createdAt);
    }

    // finance_budgets_group_no_overlap/finance_budgets_personal_no_overlap EXCLUDE 제약 위반을
    // 어댑터가 이 예외로 변환 → 컨트롤러에서 409 매핑
    public static class OverlappingPeriodException extends RuntimeException {
        public OverlappingPeriodException(String message) {
            super(message);
        }
    }
}
