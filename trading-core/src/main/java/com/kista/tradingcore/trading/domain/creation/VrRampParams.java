package com.kista.tradingcore.trading.domain.creation;

import java.math.BigDecimal;

// VR 램프 파라미터 정규화 결과 — 8필드 모두 non-null (기본값 표 SSOT는 VrRampInput.withDefaults)
public record VrRampParams(
        int initialGradient,             // gradient 초기값
        int gGraceWeeks,                 // gradient 램프 유예 주수
        int gStepWeeks,                  // gradient 램프 단계 주기 (0=램프 비활성)
        int gMax,                        // gradient 램프 상한
        BigDecimal initialPoolLimitRate, // poolLimitRate 초기값 (0~1)
        int pGraceWeeks,                 // poolLimitRate 램프 유예 주수
        int pStepWeeks,                  // poolLimitRate 램프 단계 주기 (0=램프 비활성)
        BigDecimal poolLimitFloor        // poolLimitRate 램프 하한 (0~1)
) {
    // 램프 8필드 + intervalWeeks/bandWidth 검증 — 위반 시 IllegalArgumentException
    public void validate(int intervalWeeks, BigDecimal bandWidth) {
        VrRampValidator.validateRampParams(intervalWeeks, bandWidth, this);
    }
}
