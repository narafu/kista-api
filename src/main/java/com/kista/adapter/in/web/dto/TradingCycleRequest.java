package com.kista.adapter.in.web.dto;

import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import com.kista.domain.port.in.RegisterTradingCycleUseCase;
import com.kista.domain.port.in.UpdateTradingCycleUseCase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TradingCycleRequest(
        @Schema(description = "전략 종류 (등록 시 필수)", example = "INFINITE")
        @NotNull TradingCycle.Type type,
        @Schema(description = "거래 종목 (PRIVACY는 SOXL 자동 고정, INFINITE 기본=TQQQ)", example = "TQQQ")
        Ticker ticker,
        @Schema(description = "초기 입금액 (PRIVACY: 배수 자동 산출 기준)", example = "2000.00")
        BigDecimal initialUsdDeposit,
        @Schema(description = "연속 사이클 정책 (null이면 NONE)", example = "NONE")
        TradingCycle.CycleSeedType cycleSeedType
) {
    public RegisterTradingCycleUseCase.Command toRegisterCommand() {
        return new RegisterTradingCycleUseCase.Command(type, ticker, initialUsdDeposit, cycleSeedType);
    }

    public UpdateTradingCycleUseCase.Command toUpdateCommand() {
        return new UpdateTradingCycleUseCase.Command(cycleSeedType);
    }
}
