package com.kista.domain.model.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FinanceBudget(
        UUID id,               // PK
        UUID groupId,          // FK → finance_groups.id
        UUID categoryId,       // FK → finance_categories.id
        UUID createdBy,        // FK → users.id
        LocalDate applyStartDate,
        LocalDate applyEndDate, // null이면 무기한
        long amount,            // 월 할당 예산, 원화 정수
        Instant createdAt       // DB created_at, 신규 등록 시 null
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterGroupId) {
        if (!groupId.equals(requesterGroupId)) {
            throw new SecurityException("예산에 대한 접근 권한이 없습니다");
        }
    }

    // finance_budgets_no_overlap EXCLUDE 제약 위반을 어댑터가 이 예외로 변환 → 컨트롤러에서 409 매핑
    public static class OverlappingPeriodException extends RuntimeException {
        public OverlappingPeriodException(String message) {
            super(message);
        }
    }
}
