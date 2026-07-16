package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.strategy.CycleOrderStrategy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record StrategyTypeMeta(
        @Schema(description = "enum name() 값", example = "INFINITE")
        String code,                   // enum name() 값
        @Schema(description = "전략 설명")
        String description,            // 전략 설명
        @Schema(description = "사용 가능한 티커 name() 목록 (정렬됨)")
        List<String> availableTickers, // 사용 가능한 티커 name() 목록 (정렬됨)
        @Schema(description = "basePrice 소스가 기준매매표인지 (UI basePrice 분기 SSOT)")
        boolean requiresPrivacyBase,   // basePrice 소스가 기준매매표인지 (UI basePrice 분기 SSOT)
        @Schema(description = "티커 고정 여부 (선택 불가, 단일 티커)")
        boolean tickerFixed,           // 티커 고정 여부 (선택 불가, 단일 티커)
        @Schema(description = "리버스모드 배지 표시 가드")
        boolean supportsReverseMode,   // 리버스모드 배지 표시 가드
        @Schema(description = "분할 수 옵션 (빈 목록이면 분할 개념 없음)")
        List<Integer> divisionCounts   // 분할 수 옵션 — 빈 목록이면 분할 개념 없음
) {
    public static StrategyTypeMeta from(Strategy.Type t, CycleOrderStrategy strategy) {
        List<String> tickers = t.availableTickers().stream().map(Enum::name).toList();
        return new StrategyTypeMeta(
                t.name(), t.getDescription(), tickers,
                strategy.requiresPrivacyBase(),
                tickers.size() == 1,
                strategy.supportsReverseMode(),
                strategy.availableDivisionCounts()
        );
    }
}
