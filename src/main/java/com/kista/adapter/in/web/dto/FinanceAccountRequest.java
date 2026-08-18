package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.FinanceAccount;
import com.kista.domain.model.finance.FinanceAccountCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// 등록·수정 공용
public record FinanceAccountRequest(
        @Schema(description = "계좌 타입", example = "SECURITIES")
        @NotNull FinanceAccount.Type accountType,
        @Schema(description = "계좌명", example = "토스증권 일반계좌")
        @NotBlank String name,
        @Schema(description = "계좌번호 (선택)")
        String accountNo,
        @Schema(description = "메모 (선택)")
        String memo
) {
    public FinanceAccountCommand toCommand() {
        return new FinanceAccountCommand(accountType, name, accountNo, memo);
    }
}
