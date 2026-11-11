package com.kista.shared.contract.marketcalendar;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

// 현재 KST 거래일과 휴장 여부 — GET /api/internal/marketcalendar/session 응답
// 공개 API의 MarketSessionResponse와 스키마 이름이 겹치지 않게 분리
@Schema(name = "InternalMarketSessionResponse")
public record MarketSessionResponse(
        LocalDate tradeDateKst, // 현재 시각 기준 KST 거래일 (실제 마감 30분 전 경계, 휴장일은 04:30)
        boolean tradingDay     // tradeDateKst에 US 장이 열리는지(휴장일·주말이면 false)
) {}
