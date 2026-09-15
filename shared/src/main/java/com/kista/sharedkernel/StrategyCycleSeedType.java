package com.kista.sharedkernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// 사이클 종료 후 자동 재등록 정책 — Strategy 도메인 nested enum(CycleSeedType)에서 sharedkernel로 이관(constraints.md "nested enum 정책 개정").
// 상수명 byte-identical 유지 필수 — StrategyEntity.cycle_seed_type @Enumerated(STRING) DB 컬럼과 직결.
@Getter
@RequiredArgsConstructor
public enum StrategyCycleSeedType {
    NONE("OFF"),        // holdings 0 → 전략 PAUSE
    MAINTAIN("ON(유지)"), // 종료 후 동일 initialUsdDeposit으로 재등록
    MAX("ON(MAX)");     // 종료 후 마지막 usdDeposit을 initialUsdDeposit으로 재등록

    private final String label; // 한국어 표시 이름

    public boolean isConsecutive() {
        return this != NONE;
    }
}
