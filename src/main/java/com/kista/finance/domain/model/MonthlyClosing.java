package com.kista.finance.domain.model;

import java.time.Instant;
import java.util.UUID;

// 구 domain.model.asset.AssetMonthlyCheck 대체 — 자산뿐 아니라 재무 전 영역(수입/소비/저축/자산)의
// 월 마감을 덮는다. closedAt으로 "언제 마감했는지"를 남긴다.
// 개인/그룹 혼용 도입 후 userId는 소유자 축(마감 해제해도 유지) — 옛 closedBy처럼 null로 되돌지 않는다.
public record MonthlyClosing(
        UUID id,          // PK
        UUID groupId,     // FK → finance_groups.id, null이면 개인 마감
        UUID userId,      // FK → users.id, 소유자
        String month,     // 'YYYY-MM'
        boolean completed,
        Instant closedAt, // completed=true로 전환된 시각, null 허용
        Instant createdAt // DB created_at, 신규 등록 시 null
) {
    // 접근 불가 시 SecurityException → 컨트롤러에서 403 매핑
    public void verifyAccessibleBy(UUID requesterUserId, UUID requesterGroupId) {
        boolean owned = userId.equals(requesterUserId);
        boolean sharedInMyGroup = groupId != null && groupId.equals(requesterGroupId);
        if (!owned && !sharedInMyGroup) {
            throw new SecurityException("월간 마감에 대한 접근 권한이 없습니다");
        }
    }
}
