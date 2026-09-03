package com.kista.trading.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

// VR 전략 버전 상세 — strategy_vr_version 테이블과 매핑
// gradient(G)·poolLimitRate는 전략 최초 시작일부터 경과한 주수에 따라 점진적으로 변하는 "램프" 값이다 (constraints.md "VR 공식" 후속)
public record StrategyVrDetail(
        UUID strategyVersionId,          // FK → strategy_version.id
        int intervalWeeks,               // 리밸런싱 주기 (주 단위, 1 이상)
        BigDecimal bandWidth,            // 매수·매도 사다리 밴드 폭 (% 단위, 예: 15.00)
        int recurringAmount,             // 주기당 추가 예수금 (USD, 음수=인출, 0=없음)
        int initialGradient,             // 램프 시작 시점(경과 0주)의 gradient(G) 값
        int gGraceWeeks,                 // gradient 램프가 시작되기 전 유예 주수 (이 주수 미만이면 initialGradient 유지)
        int gStepWeeks,                  // gradient가 한 단계 상승하는 주기 (주 단위)
        int gMax,                        // gradient 램프의 상한값
        BigDecimal initialPoolLimitRate, // 램프 시작 시점(경과 0주)의 poolLimitRate 값
        int pGraceWeeks,                 // poolLimitRate 램프가 시작되기 전 유예 주수
        int pStepWeeks,                  // poolLimitRate가 한 단계 하강하는 주기 (주 단위)
        BigDecimal poolLimitFloor        // poolLimitRate 램프의 하한값
) {

    // gradient 램프 1단계당 증가폭 — 재설정 서비스 등 다른 곳에서도 참조
    public static final int G_STEP = 1;
    // poolLimitRate 램프 1단계당 감소폭(5%p) — 재설정 서비스 등 다른 곳에서도 참조
    public static final BigDecimal POOL_LIMIT_STEP = new BigDecimal("0.05");

    // gradientAt: 경과 주수(weeks) 기준 gradient(G) 값 — 유예 주수 이후 gStepWeeks마다 G_STEP씩 상승, gMax에서 상한
    // gStepWeeks<=0은 gradient 램프 비활성화를 의미 — initialGradient에 고정(gMax/gGraceWeeks 무관)
    public int gradientAt(long weeks) {
        if (gStepWeeks <= 0 || weeks < gGraceWeeks) {
            return initialGradient;
        }
        long gSteps = (weeks - gGraceWeeks) / gStepWeeks + 1;
        return (int) Math.min(initialGradient + (long) G_STEP * gSteps, gMax);
    }

    // poolLimitRateAt: 경과 주수(weeks) 기준 poolLimitRate 값 — 유예 주수 이후 pStepWeeks마다 POOL_LIMIT_STEP씩 하강, poolLimitFloor에서 하한
    // pStepWeeks<=0은 poolLimitRate 램프 비활성화를 의미 — initialPoolLimitRate에 고정(poolLimitFloor/pGraceWeeks 무관)
    public BigDecimal poolLimitRateAt(long weeks) {
        if (pStepWeeks <= 0 || weeks < pGraceWeeks) {
            return initialPoolLimitRate.setScale(2, RoundingMode.HALF_UP);
        }
        long pSteps = (weeks - pGraceWeeks) / pStepWeeks + 1;
        BigDecimal decreased = initialPoolLimitRate.subtract(POOL_LIMIT_STEP.multiply(BigDecimal.valueOf(pSteps)));
        return decreased.max(poolLimitFloor).setScale(2, RoundingMode.HALF_UP);
    }
}
