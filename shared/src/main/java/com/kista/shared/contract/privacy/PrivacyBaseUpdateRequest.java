package com.kista.shared.contract.privacy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

// 관리자 PRIVACY 기준 매매표 수동 보정 — PATCH /api/internal/privacy/trade-bases/{baseId} body (마스터 필드 전체 교체)
public record PrivacyBaseUpdateRequest(
        @NotNull(message = "기준가는 필수입니다.") @Positive(message = "기준가는 0보다 커야 합니다.") BigDecimal currentCycleStart,   // 기준가
        @NotNull(message = "사이클 실현 수익은 필수입니다.") BigDecimal currentCycleRealizedPnl,       // 사이클 실현 수익($) — 손실이면 음수 허용
        @Positive(message = "평단가는 0보다 커야 합니다.") @Nullable BigDecimal avgPrice,                     // 평단가 (nullable, 값이 있으면 양수)
        @PositiveOrZero(message = "보유 수량은 0 이상이어야 합니다.") int holdings                       // 보유 수량
) {}
