package com.kista.domain.model.finance;

import java.time.Instant;
import java.util.UUID;

// 구 domain.model.asset.AssetMonthlyCheck 대체 — 자산뿐 아니라 재무 전 영역(수입/소비/저축/자산)의
// 월 마감을 덮고, closedBy/closedAt으로 "누가 언제 마감했는지"를 남긴다.
// 조회·수정이 항상 그룹 스코프로 미리 검증된 뒤 이뤄지므로(FinanceGroupPort.resolveGroupId) 별도
// verifyOwnedBy를 두지 않는다 — 구 AssetMonthlyCheck과 동일한 설계.
public record MonthlyClosing(
        UUID id,          // PK
        UUID groupId,     // FK → finance_groups.id
        UUID closedBy,    // FK → users.id, 마감 해제 상태면 null
        String month,     // 'YYYY-MM'
        boolean completed,
        Instant closedAt, // completed=true로 전환된 시각, null 허용
        Instant createdAt // DB created_at, 신규 등록 시 null
) {}
