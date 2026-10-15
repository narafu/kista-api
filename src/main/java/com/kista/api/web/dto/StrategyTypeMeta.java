package com.kista.api.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import com.kista.shared.kernel.StrategyCapability;
import com.kista.shared.kernel.StrategyType;

public record StrategyTypeMeta(
        @Schema(description = "enum name() 값", example = "INFINITE")
        String code,                   // enum name() 값
        @Schema(description = "한국어 표시 이름", example = "무한매수법")
        String label,                  // 한국어 표시 이름 — StrategyType.description이 SSOT
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
        List<Integer> divisionCounts,  // 분할 수 옵션 — 빈 목록이면 분할 개념 없음
        @Schema(description = "사용자 선택 주문유형 name() 목록 (빈 목록이면 선택 없음)")
        List<String> orderTypes,       // 사용자 선택 주문유형 — DCA만 값
        @Schema(description = "사용자 선택 방향 name() 목록 (빈 목록이면 선택 없음)")
        List<String> directions,       // 사용자 선택 방향 — DCA만 값
        @Schema(description = "DCA 실행 주기 name() 목록")
        List<String> frequencies,      // DCA 실행 주기 옵션
        @Schema(description = "DCA 조건 지표 name() 목록")
        List<String> conditionIndicators // DCA 조건 지표 옵션
) {
    public static StrategyTypeMeta from(StrategyType t) {
        StrategyCapability capability = t.capability();
        List<String> tickers = capability.availableTickers().stream().map(Enum::name).toList();
        return new StrategyTypeMeta(
                t.name(), t.getDescription(), t.getDescription(), tickers,
                capability.requiresPrivacyBase(),
                capability.tickerFixed(),
                capability.supportsReverseMode(),
                capability.divisionCounts(),
                names(capability.orderTypes()), names(capability.directions()),
                names(capability.frequencies()), names(capability.conditionIndicators())
        );
    }

    private static List<String> names(List<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).toList();
    }
}
