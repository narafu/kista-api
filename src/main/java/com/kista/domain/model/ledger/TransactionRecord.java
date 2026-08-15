package com.kista.domain.model.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TransactionRecord(
        UUID id,                    // PK
        UUID userId,                // FK → users.id
        UUID categoryId,            // FK → ledger_categories.id
        long amount,                // 원화 정수 절대값. 부호는 Category.Type.sign이 SSOT
        LocalDate transactionDate,  // 거래 발생일
        String memo,                // 자유 입력, null 허용
        Instant createdAt           // DB created_at, 신규 등록 시 null
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new SecurityException("거래내역에 대한 접근 권한이 없습니다");
        }
    }
}
