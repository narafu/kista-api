package com.kista.sharedkernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// 매매 전략 종류 — Strategy 도메인 nested enum(Type)에서 sharedkernel로 이관(constraints.md "User/Account/Strategy 공유 enum").
// 상수명 byte-identical 유지 필수 — StrategyEntity.type @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum StrategyType {
    INFINITE("무한매수법"), // 모든 StrategyTicker 지원
    PRIVACY("Fanding P전략"), // SOXL 전용
    VR("밸류리밸런싱"); // TQQQ 전용 — 밸류 기반 리밸런싱

    private final String description; // 전략 설명

    // 전략 타입별 capability 상수 SSOT — RuntimeSettings 기본 허용 분할 수(20/30/40)와 동기화
    public StrategyCapability capability() {
        return switch (this) {
            case INFINITE -> new StrategyCapability(
                    EnumSet.allOf(StrategyTicker.class), false, false, true, List.of(20, 30, 40));
            case PRIVACY -> new StrategyCapability(
                    EnumSet.of(StrategyTicker.SOXL), true, true, false, List.of());
            case VR -> new StrategyCapability(
                    EnumSet.of(StrategyTicker.TQQQ), true, false, false, List.of());
        };
    }

    // INFINITE: 전체 StrategyTicker, PRIVACY: SOXL 단일, VR: TQQQ 단일 — capability()에 위임
    public Set<StrategyTicker> availableTickers() {
        return capability().availableTickers();
    }
}
