package com.kista.tradingcore.trading.adapter.in.web.dto;

import com.kista.tradingcore.trading.domain.model.WithdrawCapitalCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

// 진행 중 사이클 투자금 인출 요청 — 예수금(USD)만 인출한다
public record CapitalWithdrawalRequest(
        @Schema(description = "인출할 예수금 (USD, 0보다 커야 함)", example = "500.00")
        @NotNull @Positive @Digits(integer = 13, fraction = 2) BigDecimal amount
) {
    public WithdrawCapitalCommand toCommand() {
        return new WithdrawCapitalCommand(amount);
    }
}
