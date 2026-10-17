package com.kista.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MetaBundle(
        @Schema(description = "전략 타입 목록")
        List<StrategyTypeMeta> strategyTypes,  // 전략 타입 목록
        @Schema(description = "티커 목록")
        List<TickerMeta> tickers,              // 티커 목록
        @Schema(description = "증권사 목록")
        List<BrokerMeta> brokers,               // 증권사 목록
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
        List<EnumMeta> financeCategoryTypes,   // 재무 카테고리 유형 목록
        @Schema(description = "주문 유형 목록")
        List<EnumMeta> orderTypes,             // 주문 유형 목록 (label은 name — OrderType에 label 없음)
        @Schema(description = "주문 방향 목록")
        List<EnumMeta> orderDirections,        // 주문 방향 목록
        @Schema(description = "DCA 실행 주기 목록")
        List<EnumMeta> dcaFrequencies,         // DCA 실행 주기 목록
        @Schema(description = "DCA 조건 연산자 목록")
        List<EnumMeta> dcaOperators,           // DCA 조건 연산자 목록
        @Schema(description = "DCA 회차 미주문 사유 목록")
        List<EnumMeta> dcaSkipReasons,         // DCA 미주문 사유 목록
        @Schema(description = "DCA 조건 지표 목록")
        List<DcaIndicatorMeta> dcaIndicators   // DCA 조건 지표 목록 (SMA 기간 옵션 포함)
) {}
