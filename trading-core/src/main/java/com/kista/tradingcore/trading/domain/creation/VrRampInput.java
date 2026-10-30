package com.kista.tradingcore.trading.domain.creation;

import com.kista.tradingcore.matching.domain.model.StrategyVrDetail;

import java.math.BigDecimal;

// VR 램프 8파라미터 원본 입력 — 미지정(null) 필드는 withDefaults(운영 기본값 표) 또는 inheritFrom(현재 버전 값)으로 채운다
public record VrRampInput(
        Integer initialGradient,         // gradient 초기값
        Integer gGraceWeeks,             // gradient 램프 유예 주수
        Integer gStepWeeks,              // gradient 램프 단계 주기 (0=램프 비활성)
        Integer gMax,                    // gradient 램프 상한
        BigDecimal initialPoolLimitRate, // poolLimitRate 초기값 (0~1)
        Integer pGraceWeeks,             // poolLimitRate 램프 유예 주수
        Integer pStepWeeks,              // poolLimitRate 램프 단계 주기 (0=램프 비활성)
        BigDecimal poolLimitFloor        // poolLimitRate 램프 하한 (0~1)
) {
    public static final VrRampInput NONE = new VrRampInput(null, null, null, null, null, null, null, null); // 전부 미지정

    private static final int DEFAULT_GRACE_WEEKS = 52; // 램프 유예 주수 기본값
    private static final int DEFAULT_STEP_WEEKS = 26;  // 램프 단계 주기 기본값

    // 미지정(null) 필드를 recurringAmount 부호(적립/거치/인출) 고정값 표로 채운다 (kista-ui RAMP_DEFAULTS_BY_MODE와 동기화)
    // 적립(>0): gradient 10/gMax 20/rate 1.0/floor 0.5, 거치(==0): gradient 10/gMax 20/rate 0.75/floor 0.5, 인출(<0): gradient 40/gMax 50/rate 0.1/floor 0.1
    public VrRampParams withDefaults(int recurringAmount) {
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

    // 미지정(null) 필드를 현재 활성 버전 값으로 채운다 (재설정용)
    public VrRampParams inheritFrom(StrategyVrDetail current) {
        return new VrRampParams(
                initialGradient != null ? initialGradient : current.initialGradient(),
                gGraceWeeks != null ? gGraceWeeks : current.gGraceWeeks(),
                gStepWeeks != null ? gStepWeeks : current.gStepWeeks(),
                gMax != null ? gMax : current.gMax(),
                initialPoolLimitRate != null ? initialPoolLimitRate : current.initialPoolLimitRate(),
                pGraceWeeks != null ? pGraceWeeks : current.pGraceWeeks(),
                pStepWeeks != null ? pStepWeeks : current.pStepWeeks(),
                poolLimitFloor != null ? poolLimitFloor : current.poolLimitFloor());
    }
}
