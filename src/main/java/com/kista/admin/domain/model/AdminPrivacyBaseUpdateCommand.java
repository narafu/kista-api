package com.kista.admin.domain.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;

// privacy.domain.model.PrivacyBaseUpdateCommand의 admin own-type 1:1 복제 — Gradle 컴파일 경계
// (:trading-core→:api 역방향 의존 금지)로 원본 타입을 import할 수 없다.
public record AdminPrivacyBaseUpdateCommand(
        @NotNull @Positive BigDecimal currentCycleStart,       // 기준가
        @NotNull BigDecimal currentCycleRealizedPnl,            // 사이클 실현 수익($) — 손실이면 음수 허용
        @Nullable BigDecimal avgPrice,                          // 평단가 (nullable)
        @PositiveOrZero int holdings                            // 보유 수량
) {
    public AdminPrivacyBaseUpdateCommand {
        if (avgPrice != null && avgPrice.signum() <= 0) {
            throw new IllegalArgumentException("avgPrice는 양수여야 합니다: " + avgPrice);
        }
    }
}
