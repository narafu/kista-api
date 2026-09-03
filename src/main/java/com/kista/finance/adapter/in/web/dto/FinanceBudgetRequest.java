package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.FinanceBudgetCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;
import java.util.UUID;

// 등록·수정 공용
public record FinanceBudgetRequest(
        @Schema(description = "카테고리 ID")
        @NotNull UUID categoryId,
        @Schema(description = "적용 시작일", example = "2026-01-01")
        @NotNull LocalDate applyStartDate,
        @Schema(description = "적용 종료일 (null이면 무기한)", example = "2026-12-31")
        LocalDate applyEndDate,
        @Schema(description = "월 할당 예산 (원화 정수, 0 초과)", example = "350000")
        @Positive long amount
) {
    public FinanceBudgetCommand toCommand() {
        return new FinanceBudgetCommand(categoryId, applyStartDate, applyEndDate, amount);
    }
}
