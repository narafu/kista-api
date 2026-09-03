package com.kista.finance.domain.model;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

// 구 domain.model.asset.Asset 대체 — category/subcategory enum+자유텍스트가 category_id FK로 승격됐다.
public record AssetSnapshot(
        UUID id,             // PK
        UUID groupId,        // FK → finance_groups.id, null이면 개인 소유
        UUID categoryId,     // FK → finance_categories.id (type=ASSET인 L1 또는 L2)
        UUID accountId,      // FK → finance_accounts.id, 계좌 없는 자산(전세임차보증금 등)은 null
        UUID userId,         // FK → users.id, 소유자
        LocalDate entryDate, // 기준 날짜
        AssetClass assetClass,
        Market market,
        String strategy,     // 자유 입력, null 허용 — 실제 자동매매 전략과 무관한 개인 메모
        String memo,         // 자유 입력, null 허용 — strategy와 별개의 자유 메모
        long amount,          // 원화 정수, 0 이상
        Instant createdAt    // DB created_at, 신규 등록 시 null
) implements GroupShareable<AssetSnapshot> {
    // 접근 불가 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyAccessibleBy(UUID requesterUserId, UUID requesterGroupId) {
        boolean owned = userId.equals(requesterUserId);
        boolean sharedInMyGroup = groupId != null && groupId.equals(requesterGroupId);
        if (!owned && !sharedInMyGroup) {
            throw new SecurityException("자산 기록에 대한 접근 권한이 없습니다");
        }
    }

    @Override
    public AssetSnapshot withGroupId(UUID groupId) {
        return new AssetSnapshot(id, groupId, categoryId, accountId, userId, entryDate, assetClass, market, strategy, memo, amount, createdAt);
    }
}
