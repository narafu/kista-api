package com.kista.domain.model.toss;

import java.time.LocalDate;

// Toss 해외 장 운영 정보 — GET /api/v1/market-calendar/US 응답 단위
public record TossMarketSession(
    LocalDate date,   // 날짜
    boolean isOpen    // 개장 여부 (휴장일·주말=false)
) {}
