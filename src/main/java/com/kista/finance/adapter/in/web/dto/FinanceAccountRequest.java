package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.FinanceAccount;
import com.kista.finance.domain.model.FinanceAccountCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 등록·수정 공용
public record FinanceAccountRequest(
        @Schema(description = "계좌 타입", example = "SECURITIES")
        @NotNull FinanceAccount.Type accountType,
        @Schema(description = "계좌명", example = "토스증권 일반계좌")
        @NotBlank String name,
        @Schema(description = "계좌번호 (선택, 숫자만)")
        @Pattern(regexp = "^\\d*$", message = "계좌번호는 숫자만 입력할 수 있습니다")
        String accountNo,
        @Schema(description = "메모 (선택)")
        String memo,
        @Schema(description = "기관 (선택, 자유 입력)", example = "토스증권")
        @Size(max = 50) String institution,
        @Schema(description = "소유자 (선택, 자유 입력)", example = "본인")
        @Size(max = 50) String owner
) {
    public FinanceAccountCommand toCommand() {
        // 빈 문자열은 null로 정규화 — 그대로 두면 hash("")가 결정론적이라 계좌번호 미입력자끼리 전역 중복 오탐 발생
        return new FinanceAccountCommand(accountType, name, blankToNull(accountNo), memo,
                blankToNull(institution), blankToNull(owner));
    }

    // 빈 문자열·공백만 입력은 미입력(null)으로 정규화
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
