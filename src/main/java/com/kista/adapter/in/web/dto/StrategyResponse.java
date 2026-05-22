package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

public record StrategyResponse(
        @Schema(description = "전략 고유 ID")
        UUID id,
        @Schema(description = "소속 계좌 ID")
        UUID accountId,
        @Schema(description = "전략 종류", example = "INFINITE")
        String type,
        @Schema(description = "전략 상태", example = "ACTIVE")
        String status,
        @Schema(description = "거래 종목", example = "TQQQ")
        String ticker,
        @Schema(description = "배수", example = "1.0")
        BigDecimal multiple
) {
    public static StrategyResponse from(Strategy s) {
        return new StrategyResponse(
                s.id(), s.accountId(),
                s.type().name(), s.status().name(),
                s.ticker().name(), s.multiple()
        );
    }
}
