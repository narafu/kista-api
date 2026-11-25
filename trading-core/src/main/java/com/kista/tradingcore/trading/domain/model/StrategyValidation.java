package com.kista.tradingcore.trading.domain.model;

import org.jspecify.annotations.Nullable;
import com.kista.shared.kernel.RecurringMode;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.trading.domain.creation.VrRampParams;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

// 등록·수정·VR 재설정 입력의 저장 없는 검증 결과 — 저장 경로와 같은 해석·검증 단계가 던진 첫 거절과 서버 파생값
public record StrategyValidation(
        List<FieldError> fieldErrors, // 거절 사유(첫 거절에서 멈추므로 0~1개)
        List<Warning> warnings,       // 저장은 되지만 알릴 것
        Derived derived               // 서버가 채우는 기본값·파생값 (해석 실패로 알 수 없는 값은 null)
) {
    // 거절 하나 — field는 요청 JSON 필드 이름, 여러 필드에 걸친 거절은 null
    public record FieldError(@Nullable String field, String detail) {

        // 저장 경로가 던진 거절을 필드에 귀속한다 — 종목 중복(409)은 ticker
        public static FieldError of(RuntimeException e) {
            String field = switch (e) {
                case FieldRejectedException f -> f.field();
                case DuplicateStrategyTickerException d -> "ticker";
                default -> null;
            };
            return new FieldError(field, e.getMessage());
        }
    }

    public record Warning(Code code, String detail) {}

    @Getter
    @RequiredArgsConstructor
    public enum Code {
        AVAILABLE_CASH_EXCEEDED("가용 예수금 초과"); // 입력 예수금이 계좌 미배정 현금보다 큼 — 등록은 막지 않는다

        private final String label; // 한국어 표시 이름 (메타 번들)
    }

    // 서버 파생값 — 등록은 해석된 런타임 기본값·시드 미리보기, VR은 램프 실효값·인출식 필요 자산, 수정은 최소 시드
    public record Derived(
            @Nullable StrategyTicker ticker,              // 해석된 종목
            @Nullable Integer divisionCount,              // 해석된 분할 수 (VR·DCA는 저장 기본값)
            @Nullable RecurringMode recurringMode,        // VR 해석된 적립 방향
            @Nullable BigDecimal bandWidth,               // VR 해석된 밴드 폭
            @Nullable Integer intervalWeeks,              // VR 해석된 리밸런싱 주기
            @Nullable VrRampParams ramp,                  // VR 램프 실효값(기본값·현재 버전 상속 반영)
            @Nullable BigDecimal requiredWithdrawalAssets, // VR 인출식 필요 최소 자산 (인출식 아니면 null)
            @Nullable BigDecimal basePrice,               // 시드 미리보기 기준가
            @Nullable BigDecimal minSeed,                 // 최소 필요 투자금
            @Nullable String skipReason,                  // 시드 미리보기 계산 불가 사유
            @Nullable BigDecimal availableCash            // 가용 예수금(계좌 미배정 현금)
    ) {
        public static final Derived EMPTY = new Derived(null, null, null, null, null, null, null, null, null, null, null);
    }

    // 입력 예수금이 가용 예수금을 넘으면 경고 — 등록은 차단하지 않는다(StrategyCreationService 종목 중복 검사 주석). 둘 중 하나라도 모르면 경고 없음
    public static List<Warning> depositWarnings(BigDecimal initialDeposit, BigDecimal availableCash) {
        if (initialDeposit == null || availableCash == null || initialDeposit.compareTo(availableCash) <= 0) {
            return List.of();
        }
        // 금액 표기는 InfiniteMinSeedGuard와 같은 #,##0.00
        return List.of(new Warning(Code.AVAILABLE_CASH_EXCEEDED,
                "입력 예수금이 가용 예수금 $%s보다 큽니다. 계좌 예수금이 부족하면 매수 주문이 거절될 수 있습니다."
                        .formatted(String.format(Locale.US, "%,.2f", availableCash))));
    }
}
