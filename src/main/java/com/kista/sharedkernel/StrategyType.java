package com.kista.sharedkernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.Set;

// 매매 전략 종류 — Strategy 도메인 nested enum(Type)에서 sharedkernel로 이관(constraints.md "nested enum 정책 개정").
// 상수명 byte-identical 유지 필수 — StrategyEntity.type @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum StrategyType {
    INFINITE("무한매수법"), // 모든 StrategyTicker 지원
    PRIVACY("Fanding P전략"), // SOXL 전용
    VR("밸류리밸런싱"); // TQQQ 전용 — 밸류 기반 리밸런싱

    private final String description; // 전략 설명

    // INFINITE: 전체 StrategyTicker, PRIVACY: SOXL 단일, VR: TQQQ 단일
    public Set<StrategyTicker> availableTickers() {
        return switch (this) {
            case PRIVACY -> EnumSet.of(StrategyTicker.SOXL);
            case VR -> EnumSet.of(StrategyTicker.TQQQ);
            case INFINITE -> EnumSet.allOf(StrategyTicker.class);
        };
    }
}
