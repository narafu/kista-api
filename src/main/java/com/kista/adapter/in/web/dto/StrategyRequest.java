package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.Strategy.Ticker;
import com.kista.domain.port.in.RegisterStrategyUseCase;
import com.kista.domain.port.in.UpdateStrategyUseCase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record StrategyRequest(
        @Schema(description = "전략 종류 (등록 시 필수)", example = "INFINITE")
        @NotNull Strategy.StrategyType type,
        @Schema(description = "거래 종목 (PRIVACY는 SOXL 자동 고정, INFINITE 기본=TQQQ)", example = "TQQQ")
        Ticker ticker,
        @Schema(description = "배수 (기본값 1.0)", example = "1.0")
        BigDecimal multiple
) {
    public RegisterStrategyUseCase.Command toRegisterCommand() {
        return new RegisterStrategyUseCase.Command(type, ticker, multiple);
    }

    public UpdateStrategyUseCase.Command toUpdateCommand() {
        return new UpdateStrategyUseCase.Command(ticker, multiple);
    }
}
