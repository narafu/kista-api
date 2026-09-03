package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.FinanceTransactionCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

// 등록·수정 공용
public record FinanceTransactionRequest(
        @Schema(description = "카테고리 ID")
        @NotNull UUID categoryId,
        @Schema(description = "거래 날짜", example = "2026-08-01")
        @NotNull LocalDate transactionDate,
        @Schema(description = "금액 (원화 정수 절대값, 0 이상)", example = "344000")
        @PositiveOrZero long amount,
        @Schema(description = "메모 (선택)")
        @Size(max = 255) String memo
) {
    public FinanceTransactionCommand toCommand() {
        return new FinanceTransactionCommand(categoryId, transactionDate, amount, memo);
    }
}
