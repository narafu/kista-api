package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

// 이상 징후 응답 DTO — 일시정지·비활성 계좌 목록
public record AnomaliesResponse(
        @Schema(description = "전략이 PAUSED 상태인 계좌 목록")
        List<AdminAccountItem> pausedAccounts,
        @Schema(description = "최근 N일(기본 7일) 거래 없는 ACTIVE 전략 계좌 목록")
        List<AdminAccountItem> inactiveAccounts
) {}
