package com.kista.domain.model.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FinanceTransaction(
        UUID id,               // PK
        UUID groupId,          // FK → finance_groups.id, null이면 개인 소유
        UUID categoryId,       // FK → finance_categories.id
        UUID userId,           // 소유자(입력자) — 개인/그룹 탭 필터 기준
        LocalDate transactionDate,
        long amount,            // 원화 정수 절대값. 부호는 FinanceCategory.Type.sign이 SSOT
        String memo,            // null 허용
        Instant createdAt       // DB created_at, 신규 등록 시 null
) {
    // 접근 불가 시 SecurityException → 컨트롤러에서 403 매핑.
    // 본인 소유(개인이든 그룹이든) 또는 현재 소속 그룹과 같은 group_id면 접근 가능.
    public void verifyAccessibleBy(UUID requesterUserId, UUID requesterGroupId) {
        boolean owned = userId.equals(requesterUserId);
        boolean sharedInMyGroup = groupId != null && groupId.equals(requesterGroupId);
        if (!owned && !sharedInMyGroup) {
            throw new SecurityException("거래내역에 대한 접근 권한이 없습니다");
        }
    }
}
