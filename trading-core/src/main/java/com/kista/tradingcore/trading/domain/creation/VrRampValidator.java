package com.kista.tradingcore.trading.domain.creation;

import java.math.BigDecimal;
import java.math.RoundingMode;

// VR 램프 파라미터·인출식 최소자산 검증 공용 유틸 — StrategyCreationService→VrRegistration(등록)·VrReconfigureService(재설정)·stats BacktestService(백테스트) 공유
// validateWithdrawalSufficiency는 등록·재설정 공용 단일 규칙 — 호출이 둘인 이유: 등록은 override V(gate)와 시장가 평가금(required)을 분리해 넘기고,
// 재설정은 현재가 조회 뒤에만 자산을 알 수 있어 램프 검증(가격 조회 전)과 시점이 다르다(합치면 램프 오류에도 브로커 호출이 생긴다)
// (docs/agents/modules/trading-formulas.md "VR 공식" 변경 금지 대상 — 검증 조건 자체는 옮기기 전과 byte-for-byte 동일)
public final class VrRampValidator {

    private VrRampValidator() {
    }

    // gradient/poolLimitRate 램프 8필드 + intervalWeeks/bandWidth 검증
    public static void validateRampParams(int intervalWeeks, BigDecimal bandWidth, VrRampParams ramp) {
        int initialGradient = ramp.initialGradient();
        int gGraceWeeks = ramp.gGraceWeeks();
        int gStepWeeks = ramp.gStepWeeks();
        int gMax = ramp.gMax();
        BigDecimal initialPoolLimitRate = ramp.initialPoolLimitRate();
        int pGraceWeeks = ramp.pGraceWeeks();
        int pStepWeeks = ramp.pStepWeeks();
        BigDecimal poolLimitFloor = ramp.poolLimitFloor();
        if (intervalWeeks <= 0) {
            throw new IllegalArgumentException("VR 전략의 리밸런싱 주기(intervalWeeks)는 1 이상이어야 합니다");
        }
        if (bandWidth == null || bandWidth.signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 밴드 폭(bandWidth)은 0보다 커야 합니다");
        }
        if (initialGradient <= 0) {
            throw new IllegalArgumentException("VR 전략의 초기 gradient(initialGradient)는 0보다 커야 합니다");
        }
        if (gStepWeeks < 0) {
            throw new IllegalArgumentException("VR 전략의 gradient 스텝 주기(gStepWeeks)는 0 이상이어야 합니다");
        }
        if (gGraceWeeks < 0) {
            throw new IllegalArgumentException("VR 전략의 gradient 유예 주수(gGraceWeeks)는 0 이상이어야 합니다");
        }
        // gStepWeeks=0은 gradient 램프 비활성화 — 이때 gMax는 계산에 사용되지 않으므로 0을 허용
        if (gStepWeeks > 0 && gMax < initialGradient) {
            throw new IllegalArgumentException("VR 전략의 gradient 상한(gMax)은 initialGradient 이상이어야 합니다");
        }
        if (pStepWeeks < 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 스텝 주기(pStepWeeks)는 0 이상이어야 합니다");
        }
        if (pGraceWeeks < 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 유예 주수(pGraceWeeks)는 0 이상이어야 합니다");
        }
        if (initialPoolLimitRate.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("VR 전략의 초기 poolLimitRate(initialPoolLimitRate)는 1 이하여야 합니다");
        }
        // poolLimitFloor 범위는 pStepWeeks와 무관하게 항상 검증 — DB CHECK(pool_limit_floor <= initial_pool_limit_rate)와
        // 어긋나는 값이 여기서 걸러지지 않으면 INSERT 시 매핑되지 않은 DataIntegrityViolationException → 500으로 새는 것을 방지
        if (poolLimitFloor == null || poolLimitFloor.signum() < 0 || poolLimitFloor.compareTo(initialPoolLimitRate) > 0) {
            throw new IllegalArgumentException(
                    "VR 전략의 poolLimitRate 하한(poolLimitFloor)은 0 이상 initialPoolLimitRate 이하여야 합니다");
        }
        // pStepWeeks=0은 poolLimitRate 램프 비활성화(항상 initialPoolLimitRate 유지) — 이때는 poolLimitFloor=0도 허용
        if (pStepWeeks > 0 && poolLimitFloor.signum() <= 0) {
            throw new IllegalArgumentException("VR 전략의 poolLimitRate 램프는 poolLimitFloor가 0보다 커야 합니다");
        }
    }

    // 인출식(recurringAmount<0) 최소자산 검증 — gateCheckAssets: 거치식/인출식 "0보다 커야" 게이트(override 값 허용 가능한 쪽)
    // requiredCheckAssets: 인출액 대비 필요자산 비교 기준(등록 시엔 override 우회 방지를 위해 실제 시장가 기준 값을 별도로 넘김,
    // 재설정 시엔 override 개념이 없어 두 값이 동일)
    public static void validateWithdrawalSufficiency(int recurringAmount, int intervalWeeks,
                                               BigDecimal gateCheckAssets, BigDecimal requiredCheckAssets) {
        if (recurringAmount <= 0 && gateCheckAssets.signum() <= 0) {
            throw new IllegalArgumentException("VR 거치식/인출식은 초기 V값과 초기 예수금 중 하나는 0보다 커야 합니다");
        }
        if (recurringAmount < 0) {
            BigDecimal required = BigDecimal.valueOf(Math.abs((long) recurringAmount))
                    .multiply(BigDecimal.valueOf(100))
                    .multiply(BigDecimal.valueOf(4))
                    .divide(BigDecimal.valueOf(intervalWeeks), 2, RoundingMode.HALF_UP);
            if (requiredCheckAssets.compareTo(required) < 0) {
                throw new IllegalArgumentException("인출식 VR 전략의 초기 자산은 " + required + " 이상이어야 합니다");
            }
        }
    }
}
