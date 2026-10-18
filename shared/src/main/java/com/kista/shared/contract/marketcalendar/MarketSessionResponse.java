package com.kista.shared.contract.marketcalendar;

import com.kista.shared.kernel.MarketSession;
import io.swagger.v3.oas.annotations.media.Schema;

// 현재 미국 시장 세션 — GET /api/internal/marketcalendar/session 응답
// 공개 API의 MarketSessionResponse와 스키마 이름이 겹치지 않게 분리
@Schema(name = "InternalMarketSessionResponse")
public record MarketSessionResponse(
        MarketSession session, // DIRECT / BLOCKED
        boolean isDst          // 미국 서머타임 여부
) {}
