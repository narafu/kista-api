package com.kista.sharedkernel;

import java.math.BigDecimal;

// 전략별 신규 생성 정책 — admin RuntimeSettings.strategies() 값 타입, trading StrategyCreationResolvers가 그대로 소비
public record StrategyCreationSettings(
        boolean enabled, // 신규 전략 생성 허용 여부
        StrategyFieldSettings<StrategyTicker> ticker, // 종목 생성 설정
        StrategyFieldSettings<Integer> divisionCount, // 무한매수 분할 수 설정
        StrategyFieldSettings<RecurringMode> recurringMode, // VR 정기 입출금 방향 설정
        StrategyFieldSettings<BigDecimal> bandWidth, // VR 밴드 폭 설정 (%, StrategyVrDetail.bandWidth와 동일한 BigDecimal 정밀도 유지)
        StrategyFieldSettings<Integer> intervalWeeks // VR 주기 설정
) {
    public StrategyCreationSettings {
        // recurringMode 고정 정책은 signed recurringAmount의 유일한 무입출금 표현인 HOLD만 허용한다.
        if (recurringMode != null && !recurringMode.customizable()
                && (recurringMode.defaultValue() != RecurringMode.HOLD
                || !recurringMode.allowedValues().equals(java.util.List.of(RecurringMode.HOLD)))) {
            throw new IllegalArgumentException("non-customizable recurringMode must allow and default to HOLD only");
        }
    }
}
