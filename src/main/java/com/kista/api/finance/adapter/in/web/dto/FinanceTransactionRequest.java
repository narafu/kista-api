package com.kista.api.finance.adapter.in.web.dto;

import org.jspecify.annotations.Nullable;
import com.kista.api.finance.domain.model.FinanceTransactionCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

// 등록·수정 공용
public record FinanceTransactionRequest(
        @Schema(description = "카테고리 ID")
        @NotNull(message = "카테고리는 필수입니다.") UUID categoryId,
        @Schema(description = "거래 날짜", example = "2026-08-01")
        @NotNull(message = "거래 날짜는 필수입니다.") LocalDate transactionDate,
        @Schema(description = "금액 (원화 정수 절대값, 0 이상)", example = "344000")
        @PositiveOrZero(message = "금액은 0 이상이어야 합니다.") long amount,
        @Schema(description = "메모 (선택)")
        @Size(max = 255, message = "메모는 255자 이내여야 합니다.") @Nullable String memo
) {
    public FinanceTransactionCommand toCommand() {
        return new FinanceTransactionCommand(categoryId, transactionDate, amount, memo);
    }
}
