package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.FinanceBudget;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

public record FinanceBudgetResponse(
        @Schema(description = "예산 고유 ID")
        UUID id,
        @Schema(description = "카테고리 ID")
        UUID categoryId,
        @Schema(description = "적용 시작일", example = "2026-01-01")
        LocalDate applyStartDate,
        @Schema(description = "적용 종료일 (null이면 무기한)", example = "2026-12-31")
        LocalDate applyEndDate,
        @Schema(description = "월 할당 예산 (원화 정수)", example = "350000")
        long amount
) {
    public static FinanceBudgetResponse from(FinanceBudget b) {
        return new FinanceBudgetResponse(b.id(), b.categoryId(), b.applyStartDate(), b.applyEndDate(), b.amount());
    }
}
