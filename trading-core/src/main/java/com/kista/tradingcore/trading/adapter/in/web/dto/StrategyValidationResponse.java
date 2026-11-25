package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.shared.kernel.RecurringMode;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.creation.VrRampParams;
import com.kista.tradingcore.trading.domain.model.StrategyValidation;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

// 등록·수정·VR 재설정 검증 전용 엔드포인트 응답 — 통과·거절 모두 200, 저장 경로와 같은 해석·검증 결과
public record StrategyValidationResponse(
        @Schema(description = "거절 사유 (통과면 빈 목록, 첫 거절에서 멈춰 최대 1개). 실제 요청이 400·409로 돌려주는 detail과 같은 문장")
        List<FieldError> fieldErrors,
        @Schema(description = "저장은 되지만 알릴 경고 (없으면 빈 목록)")
        List<Warning> warnings,
        @Schema(description = "서버가 채우는 기본값·파생값 (알 수 없는 값은 null)")
        Derived derived
) {
    public static StrategyValidationResponse from(StrategyValidation v) {
        StrategyValidation.Derived d = v.derived();
        return new StrategyValidationResponse(
                v.fieldErrors().stream().map(e -> new FieldError(e.field(), e.detail())).toList(),
                v.warnings().stream().map(w -> new Warning(w.code(), w.detail())).toList(),
                new Derived(d.ticker(), d.divisionCount(), d.recurringMode(), d.bandWidth(), d.intervalWeeks(), d.ramp(),
                        d.requiredWithdrawalAssets(), d.basePrice(), d.minSeed(), d.skipReason(), d.availableCash()));
    }

    // OpenAPI 스키마 이름은 다른 FieldError·Warning 레코드와 겹치지 않게 명시
    @Schema(name = "StrategyValidationFieldError")
    public record FieldError(
            @Schema(description = "요청 JSON 필드 이름 (여러 필드에 걸친 거절은 null)", example = "scheduledStartDate")
            @Nullable String field,
            @Schema(description = "한국어 거절 문장")
            String detail
    ) {}

    @Schema(name = "StrategyValidationWarning")
    public record Warning(
            @Schema(description = "경고 코드 (라벨은 메타 strategyValidationWarningCodes)", example = "AVAILABLE_CASH_EXCEEDED")
            StrategyValidation.Code code,
            @Schema(description = "한국어 안내 문장")
            String detail
    ) {}

    @Schema(name = "StrategyValidationDerived")
    public record Derived(
            @Schema(description = "해석된 종목 (등록)") @Nullable StrategyTicker ticker,
            @Schema(description = "해석된 분할 수 (등록)") @Nullable Integer divisionCount,
            @Schema(description = "VR 해석된 적립 방향") @Nullable RecurringMode recurringMode,
            @Schema(description = "VR 해석된 밴드 폭") @Nullable BigDecimal bandWidth,
            @Schema(description = "VR 해석된 리밸런싱 주기") @Nullable Integer intervalWeeks,
            @Schema(description = "VR 램프 실효값 (등록=모드별 기본값, 재설정=현재 버전 상속 반영)") @Nullable VrRampParams ramp,
            @Schema(description = "VR 인출식 필요 최소 자산 (인출식 아니면 null)") @Nullable BigDecimal requiredWithdrawalAssets,
            @Schema(description = "시드 미리보기 기준가 (등록)") @Nullable BigDecimal basePrice,
            @Schema(description = "최소 필요 투자금 (등록 시드 미리보기, 수정은 INFINITE 시드 수정 기준)") @Nullable BigDecimal minSeed,
            @Schema(description = "시드 미리보기 계산 불가 사유", example = "NO_PRIVACY_BASE") @Nullable String skipReason,
            @Schema(description = "가용 예수금(계좌 미배정 현금, 모의계좌·조회 실패면 null)") @Nullable BigDecimal availableCash
    ) {}
}
