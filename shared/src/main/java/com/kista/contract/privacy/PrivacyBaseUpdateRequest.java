package com.kista.contract.privacy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

// 관리자 PRIVACY 기준 매매표 수동 보정 — PATCH /api/internal/privacy/trade-bases/{baseId} body (마스터 필드 전체 교체)
public record PrivacyBaseUpdateRequest(
        @NotNull @Positive BigDecimal currentCycleStart,   // 기준가
        @NotNull BigDecimal currentCycleRealizedPnl,       // 사이클 실현 수익($) — 손실이면 음수 허용
        @Positive BigDecimal avgPrice,                     // 평단가 (nullable, 값이 있으면 양수)
        @PositiveOrZero int holdings                       // 보유 수량
) {}
