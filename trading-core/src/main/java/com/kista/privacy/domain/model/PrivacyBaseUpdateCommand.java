package com.kista.privacy.domain.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;

// 관리자 PRIVACY 기준 매매표 수동 보정 — 마스터 필드 전체 교체
public record PrivacyBaseUpdateCommand(
        @NotNull @Positive BigDecimal currentCycleStart,       // 기준가
        @NotNull BigDecimal currentCycleRealizedPnl,            // 사이클 실현 수익($) — 손실이면 음수 허용
        @Nullable BigDecimal avgPrice,                          // 평단가 (nullable)
        @PositiveOrZero int holdings                            // 보유 수량
) {
    public PrivacyBaseUpdateCommand {
        if (avgPrice != null && avgPrice.signum() <= 0) {
            throw new IllegalArgumentException("avgPrice는 양수여야 합니다: " + avgPrice);
        }
    }
}
