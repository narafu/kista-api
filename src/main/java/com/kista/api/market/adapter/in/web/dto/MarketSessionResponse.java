package com.kista.api.market.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

// 현재 KST 거래일과 휴장 여부 응답 — UI 휴장일 안내 판단용
public record MarketSessionResponse(
        @Schema(description = "현재 시각 기준 KST 거래일 (그 날짜 실제 마감 30분 전부터 익일, 휴장일은 04:30 KST 기준)", example = "2026-07-20") LocalDate tradeDateKst,
        @Schema(description = "tradeDateKst에 미국 장이 열리는지 (휴장일·주말이면 false)") boolean tradingDay
) {}
