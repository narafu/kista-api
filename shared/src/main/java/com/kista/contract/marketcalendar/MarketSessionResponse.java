package com.kista.contract.marketcalendar;

import com.kista.sharedkernel.MarketSession;

// 현재 미국 시장 세션 — GET /api/internal/marketcalendar/session 응답
public record MarketSessionResponse(
        MarketSession session, // DIRECT / BLOCKED
        boolean isDst          // 미국 서머타임 여부
) {}
