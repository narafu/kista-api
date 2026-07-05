package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.UpdateStrategyCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TradingCycleRequest(
        @Schema(description = "전략 종류 (등록 시 필수)", example = "INFINITE")
        @NotNull Strategy.Type type,
        @Schema(description = "거래 종목 (PRIVACY는 SOXL 자동 고정, VR은 TQQQ 자동 고정, INFINITE 기본=TQQQ)", example = "TQQQ")
        Strategy.Ticker ticker,
        @Schema(description = "초기 입금액 (PRIVACY: 배수 자동 산출 기준, VR: 예수금=초기 pool)", example = "2000.00")
        BigDecimal initialUsdDeposit,
        @Schema(description = "연속 사이클 정책 (null이면 NONE)", example = "NONE")
        Strategy.CycleSeedType cycleSeedType,
        @Schema(description = "분할 수 (20/30/40, null이면 20)", example = "20")
        Integer divisionCount,
        // VR 전용 필드 (비VR 등록 시 null — @NotNull 없음, 서비스 검증)
        @Schema(description = "VR: 주식 평가금 (초기 V값)", example = "3000.00")
        BigDecimal initialValue,
        @Schema(description = "VR: 리밸런싱 주기 (주 단위, 1 이상)", example = "4")
        Integer intervalWeeks,
        @Schema(description = "VR: 매수·매도 사다리 밴드 폭 (%, 예: 15.00)", example = "15.00")
        BigDecimal bandWidth,
        @Schema(description = "VR: 주기당 추가 예수금 (USD, 음수=인출, 0=없음)", example = "0")
        Integer recurringAmount
) {
    public RegisterStrategyCommand toRegisterCommand() {
        return new RegisterStrategyCommand(type, ticker, initialUsdDeposit, cycleSeedType,
                divisionCount != null ? divisionCount : 20,
                initialValue, intervalWeeks, bandWidth, recurringAmount);
    }

    public UpdateStrategyCommand toUpdateCommand() {
        return new UpdateStrategyCommand(cycleSeedType, initialUsdDeposit);
    }
}
