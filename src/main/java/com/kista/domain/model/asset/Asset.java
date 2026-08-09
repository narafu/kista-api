package com.kista.domain.model.asset;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record Asset(
        UUID id,                 // PK
        UUID userId,             // FK → users.id
        LocalDate entryDate,     // 기준 날짜
        AssetCategory category,  // 투자/예적금/대출/부동산
        String subcategory,      // 자유 입력
        String institution,      // 자유 입력, null 허용
        String assetClass,       // 자유 입력
        String strategy,         // 자유 입력, null 허용 — 실제 자동매매 전략과 무관
        long amount,              // 원화 정수, 0 이상
        Instant createdAt        // DB created_at, 신규 등록 시 null
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new SecurityException("자산 기록에 대한 접근 권한이 없습니다");
        }
    }
}
