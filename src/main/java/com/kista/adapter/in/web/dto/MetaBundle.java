package com.kista.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MetaBundle(
        @Schema(description = "전략 타입 목록")
        List<StrategyTypeMeta> strategyTypes,  // 전략 타입 목록
        @Schema(description = "티커 목록")
        List<TickerMeta> tickers,              // 티커 목록
        @Schema(description = "증권사 목록")
        List<EnumMeta> brokers,                // 증권사 목록
        @Schema(description = "전략 상태 목록")
        List<EnumMeta> strategyStatuses,       // 전략 상태 목록
        @Schema(description = "연속 사이클 정책 목록")
        List<EnumMeta> cycleSeedTypes,         // 연속 사이클 정책 목록
        @Schema(description = "자산군 목록")
        List<EnumMeta> assetClasses,           // 자산군 목록
        @Schema(description = "시장 목록")
        List<EnumMeta> markets,                // 시장 목록
        @Schema(description = "재무 계좌 유형 목록")
        List<EnumMeta> financeAccountTypes,    // 재무 계좌 유형 목록
        @Schema(description = "재무 카테고리 유형 목록")
        List<EnumMeta> financeCategoryTypes    // 재무 카테고리 유형 목록
) {}
