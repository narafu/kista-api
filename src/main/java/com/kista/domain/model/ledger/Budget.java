package com.kista.domain.model.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Budget(
        UUID id,                  // PK
        UUID userId,              // FK → users.id
        UUID categoryId,          // FK → ledger_categories.id
        LocalDate applyStartDate, // 이 날짜부터 다음 행 시작일 전까지 유효 (기간 중첩 구조적 불가)
        long amount,              // 월 할당 예산, 원화 정수. 0이면 예산 종료를 의미
        Instant createdAt         // DB created_at, 신규 등록 시 null
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new SecurityException("예산에 대한 접근 권한이 없습니다");
        }
    }
}
