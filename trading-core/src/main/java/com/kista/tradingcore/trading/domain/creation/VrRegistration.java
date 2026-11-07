package com.kista.tradingcore.trading.domain.creation;

import com.kista.tradingcore.trading.domain.creation.StrategyCreationResolver.ResolvedCreation;
import com.kista.tradingcore.trading.domain.model.RegisterStrategyCommand;

import java.math.BigDecimal;

// VR 등록 입력 해석 결과 — 해석된 V값과 정규화된 램프 파라미터 (비VR 등록에는 쓰지 않는다)
public record VrRegistration(BigDecimal vrValue, VrRampParams ramp) {

    // VR 전략 파라미터 검증 (서비스 계층 — DTO @NotNull 없이 여기서 처리) — V값은 시장가×보유수량 기준
    // 램프 파라미터(gradient/poolLimitRate 경과주수 함수)는 RuntimeSettings 정책 밖 — 요청값 정규화 후 여기서 직접 검증
    public static VrRegistration resolve(RegisterStrategyCommand cmd, ResolvedCreation resolved,
                                       BigDecimal initialUsdDeposit, BigDecimal evaluatedStockValue) {
        int recurringAmount = resolved.recurringAmount(); // VrCreationResolver가 VR에서는 항상 non-null로 해석
        BigDecimal vrValue = resolveVrValue(cmd, evaluatedStockValue);
        // VR 램프 파라미터 정규화 — 기본값 표는 VrRampInput.withDefaults()가 SSOT(백테스트와 공유)
        VrRampParams ramp = cmd.vrRamp().withDefaults(recurringAmount);
        validate(resolved.intervalWeeks(), resolved.bandWidth(), recurringAmount,
                vrValue, initialUsdDeposit, evaluatedStockValue, ramp);
        return new VrRegistration(vrValue, ramp);
    }

    // VR V값 우선순위 — 초기 V 직접 입력(>0)이 있으면 그 값을, 없으면 평가금(전일종가×보유수량)을 사용한다.
    // 실제 포지션(CyclePosition)·startAmount는 이 override와 무관하게 항상 evaluatedStockValue(실제 시장가) 기준을 유지한다.
    private static BigDecimal resolveVrValue(RegisterStrategyCommand cmd, BigDecimal evaluatedStockValue) {
        BigDecimal explicit = cmd.initialVrValue();
        if (explicit != null && explicit.signum() < 0) {
            throw new IllegalArgumentException("VR 전략의 초기 V값은 0 이상이어야 합니다.");
        }
        return explicit != null && explicit.signum() > 0 ? explicit : evaluatedStockValue;
    }

    // VR 전용 파라미터 검증 — 각 항목이 null이거나 범위 위반이면 IllegalArgumentException
    // vrValue: override 우선순위로 해석된 V값(게이트 판정용) — resolveVrValue() 참고
    // evaluatedStockValue: 실제 시장가 기준 평가금(전일종가×보유수량) — 인출식 최소자산 검증은 이 값만 사용해 override로 우회할 수 없게 한다
    private static void validate(Integer intervalWeeks,
                                 BigDecimal bandWidth, int recurringAmount,
                                 BigDecimal vrValue, BigDecimal initialUsdDeposit, BigDecimal evaluatedStockValue,
                                 VrRampParams ramp) {
        VrRampValidator.requireIntervalWeeks(intervalWeeks);
        BigDecimal initialAssets = vrValue.add(initialUsdDeposit);
        BigDecimal evaluatedAssets = evaluatedStockValue.add(initialUsdDeposit);

        // gate 체크는 override 가능한 vrValue 기준, 인출액 대비 필요자산 비교는 시장가(evaluatedStockValue) 기준
        // — override로 우회할 수 없게 별도 값을 넘긴다 (VrRampValidator 참고)
        VrRampValidator.validateWithdrawalSufficiency(recurringAmount, intervalWeeks, initialAssets, evaluatedAssets);

        // 램프 파라미터 + intervalWeeks/bandWidth 검증 — 정규화된(null 아님) 값 기준
        ramp.validate(intervalWeeks, bandWidth);
    }
}
