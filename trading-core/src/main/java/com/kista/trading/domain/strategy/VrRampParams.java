package com.kista.trading.domain.strategy;

import java.math.BigDecimal;

// VR 램프 파라미터 정규화 결과 — 8필드 모두 non-null, 전략 등록·백테스트가 공유하는 기본값 표의 SSOT
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
    private static final int DEFAULT_GRACE_WEEKS = 52; // 램프 유예 주수 기본값
    private static final int DEFAULT_STEP_WEEKS = 26;  // 램프 단계 주기 기본값

    // 미지정(null) 필드를 recurringAmount 부호(적립/거치/인출) 고정값 표로 채운다 (kista-ui RAMP_DEFAULTS_BY_MODE와 동기화)
    // 적립(>0): gradient 10/gMax 20/rate 1.0/floor 0.5, 거치(==0): gradient 10/gMax 20/rate 0.75/floor 0.5, 인출(<0): gradient 40/gMax 50/rate 0.1/floor 0.1
    public static VrRampParams withDefaults(int recurringAmount,
                                            Integer initialGradient, Integer gGraceWeeks, Integer gStepWeeks, Integer gMax,
                                            BigDecimal initialPoolLimitRate, Integer pGraceWeeks, Integer pStepWeeks,
                                            BigDecimal poolLimitFloor) {
        int defaultInitialGradient = recurringAmount < 0 ? 40 : 10;
        int defaultGMax = recurringAmount < 0 ? 50 : 20;
        BigDecimal defaultInitialPoolLimitRate = recurringAmount > 0 ? BigDecimal.ONE
                : recurringAmount == 0 ? new BigDecimal("0.75") : new BigDecimal("0.1");
        BigDecimal defaultPoolLimitFloor = recurringAmount < 0 ? new BigDecimal("0.1") : new BigDecimal("0.5");

        return new VrRampParams(
                initialGradient != null ? initialGradient : defaultInitialGradient,
                gGraceWeeks != null ? gGraceWeeks : DEFAULT_GRACE_WEEKS,
                gStepWeeks != null ? gStepWeeks : DEFAULT_STEP_WEEKS,
                gMax != null ? gMax : defaultGMax,
                initialPoolLimitRate != null ? initialPoolLimitRate : defaultInitialPoolLimitRate,
                pGraceWeeks != null ? pGraceWeeks : DEFAULT_GRACE_WEEKS,
                pStepWeeks != null ? pStepWeeks : DEFAULT_STEP_WEEKS,
                poolLimitFloor != null ? poolLimitFloor : defaultPoolLimitFloor);
    }

    // 램프 8필드 + intervalWeeks/bandWidth 검증 — 위반 시 IllegalArgumentException
    public void validate(int intervalWeeks, BigDecimal bandWidth) {
        VrRampValidator.validateRampParams(intervalWeeks, bandWidth,
                initialGradient, gGraceWeeks, gStepWeeks, gMax,
                initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor);
    }
}
