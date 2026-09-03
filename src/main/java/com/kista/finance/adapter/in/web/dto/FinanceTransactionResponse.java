package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.FinanceTransaction;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

public record FinanceTransactionResponse(
        @Schema(description = "거래내역 고유 ID")
        UUID id,
        @Schema(description = "카테고리 ID")
        UUID categoryId,
        @Schema(description = "그룹 ID (null이면 개인 소유 — 본인만 조회·전환 가능)")
        UUID groupId,
        @Schema(description = "거래 날짜", example = "2026-08-01")
        LocalDate transactionDate,
        @Schema(description = "금액 (원화 정수 절대값)", example = "344000")
        long amount,
        @Schema(description = "메모")
        String memo
) {
    public static FinanceTransactionResponse from(FinanceTransaction t) {
        return new FinanceTransactionResponse(t.id(), t.categoryId(), t.groupId(), t.transactionDate(), t.amount(), t.memo());
    }
}
