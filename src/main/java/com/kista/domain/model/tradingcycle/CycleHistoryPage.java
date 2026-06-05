package com.kista.domain.model.tradingcycle;

import java.time.Instant;
import java.util.List;

// 커서 기반 페이지네이션 결과 — items + 다음 페이지 커서
public record CycleHistoryPage(
        List<AccountCycleHistoryEntry> items,
        Instant nextCursor,  // null이면 마지막 페이지
        boolean hasMore
) {}
