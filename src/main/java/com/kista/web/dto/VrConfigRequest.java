package com.kista.web.dto;

import com.kista.trading.domain.model.ReconfigureVrCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;

// VR 전략 운영 중 재설정 요청 — 모든 필드 optional(미지정=현재 활성 버전 값 상속), 강한 검증은 서비스 계층에서 수행
public record VrConfigRequest(
        @Schema(description = "밴드 폭 (%) — 미지정 시 현재값 유지", example = "15.00")
        BigDecimal bandWidth,
        @Schema(description = "리밸런싱 주기 (주) — 미지정 시 현재값 유지", example = "4")
        Integer intervalWeeks,
        @Schema(description = "주기당 추가 예수금 (USD, 음수=인출) — 미지정 시 현재값 유지", example = "0")
        Integer recurringAmount,
        @Schema(description = "램프: 경과 0주 gradient — 미지정 시 현재값 유지", example = "10")
        Integer initialGradient,
        @Schema(description = "램프: gradient 유예 주수 — 미지정 시 현재값 유지", example = "52")
        Integer gGraceWeeks,
        @Schema(description = "램프: gradient 단계 주기 — 미지정 시 현재값 유지", example = "26")
        Integer gStepWeeks,
        @Schema(description = "램프: gradient 상한 — 미지정 시 현재값 유지", example = "20")
        Integer gMax,
        @Schema(description = "램프: 경과 0주 poolLimitRate — 미지정 시 현재값 유지", example = "0.75")
        BigDecimal initialPoolLimitRate,
        @Schema(description = "램프: poolLimitRate 유예 주수 — 미지정 시 현재값 유지", example = "52")
        Integer pGraceWeeks,
        @Schema(description = "램프: poolLimitRate 단계 주기 — 미지정 시 현재값 유지", example = "26")
        Integer pStepWeeks,
        @Schema(description = "램프: poolLimitRate 하한 — 미지정 시 현재값 유지", example = "0.50")
        BigDecimal poolLimitFloor,
        @Schema(description = "자본 주입: 매수단가 편입할 주식 수 (null=주입 없음)", example = "10")
        @Min(0) Integer injectShares,
        @Schema(description = "자본 주입: 위 주식의 매수단가 (injectShares>0이면 필수)", example = "45.50")
        BigDecimal injectSharePrice,
        @Schema(description = "자본 주입: 추가 예수금 (USD, null/0=주입 없음)", example = "500.00")
        BigDecimal injectDeposit,
        @Schema(description = "자본 인출: 보유 수량에서 차감할 주식 수 (null=인출 없음, 보유 수량 초과 불가)", example = "5")
        @Min(0) Integer withdrawShares,
        @Schema(description = "자본 인출: 예수금 차감액 (USD, null/0=인출 없음, 보유 예수금 초과 불가)", example = "200.00")
        BigDecimal withdrawDeposit
) {
    public ReconfigureVrCommand toCommand() {
        return new ReconfigureVrCommand(bandWidth, intervalWeeks, recurringAmount,
                initialGradient, gGraceWeeks, gStepWeeks, gMax,
                initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor,
                injectShares, injectSharePrice, injectDeposit,
                withdrawShares, withdrawDeposit);
    }
}
