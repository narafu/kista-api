package com.kista.domain.model.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FinanceTransaction(
        UUID id,               // PK
        UUID groupId,          // FK → finance_groups.id
        UUID categoryId,       // FK → finance_categories.id
        UUID createdBy,        // 입력자 — 개인/그룹 탭 필터 기준
        LocalDate transactionDate,
        long amount,            // 원화 정수 절대값. 부호는 FinanceCategory.Type.sign이 SSOT
        String memo,            // null 허용
        Instant createdAt       // DB created_at, 신규 등록 시 null
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterGroupId) {
        if (!groupId.equals(requesterGroupId)) {
            throw new SecurityException("거래내역에 대한 접근 권한이 없습니다");
        }
    }
}
