package com.kista.domain.model.toss;

import java.time.LocalDate;
import java.time.OffsetDateTime;

// Toss 해외 장 운영 정보 — GET /api/v1/market-calendar/US?date={date} 단건 응답
// regularMarket != null 이면 정규장 개장일
public record TossMarketSession(
    LocalDate      date,           // 날짜 (US 현지 기준)
    SessionHours   preMarket,      // 프리마켓 — 휴장일이면 null
    SessionHours   regularMarket,  // 정규장 — 휴장일·주말이면 null
    SessionHours   afterMarket     // 애프터마켓 — 휴장일이면 null
) {
    // 정규장이 있으면 개장일
    public boolean isOpen() { return regularMarket != null; }

    // 세션 시작·종료 시각 (ISO 8601 UTC)
    public record SessionHours(OffsetDateTime startTime, OffsetDateTime endTime) {}
}
