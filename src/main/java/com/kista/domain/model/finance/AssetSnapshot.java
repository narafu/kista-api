package com.kista.domain.model.finance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 구 domain.model.asset.Asset 대체 — category/subcategory enum+자유텍스트가 category_id FK로 승격됐다.
public record AssetSnapshot(
        UUID id,             // PK
        UUID groupId,        // FK → finance_groups.id
        UUID categoryId,     // FK → finance_categories.id (type=ASSET인 L1 또는 L2)
        UUID accountId,      // FK → finance_accounts.id, 계좌 없는 자산(전세임차보증금 등)은 null
        UUID createdBy,      // FK → users.id
        LocalDate entryDate, // 기준 날짜
        AssetClass assetClass,
        Market market,
        String strategy,     // 자유 입력, null 허용 — 실제 자동매매 전략과 무관한 개인 메모
        long amount,          // 원화 정수, 0 이상
        Instant createdAt    // DB created_at, 신규 등록 시 null
) {
    // 소유권 불일치 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyOwnedBy(UUID requesterGroupId) {
        if (!groupId.equals(requesterGroupId)) {
            throw new SecurityException("자산 기록에 대한 접근 권한이 없습니다");
        }
    }
}
