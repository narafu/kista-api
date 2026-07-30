package com.kista.adapter.in.web.dto;

import com.kista.domain.model.strategy.RegisterStrategyCommand;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.UpdateStrategyCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TradingCycleRequest(
        @Schema(description = "전략 종류 (등록 시 필수)", example = "INFINITE")
        @NotNull Strategy.Type type,
        @Schema(description = "거래 종목 (null이면 런타임 기본값, PRIVACY=SOXL/VR=TQQQ 외 명시값은 400)", example = "TQQQ")
        Strategy.Ticker ticker,
        @Schema(description = "초기 입금액 (PRIVACY: 배수 자동 산출 기준, VR: 예수금=초기 pool)", example = "2000.00")
        BigDecimal initialUsdDeposit,
        @Schema(description = "연속 사이클 정책 (null이면 NONE)", example = "NONE")
        Strategy.CycleSeedType cycleSeedType,
        @Schema(description = "분할 수 (null이면 런타임 기본값)", example = "20")
        Integer divisionCount,
        @Schema(description = "중간부터 시작 — 등록 시점 기존 보유 수량 (null/0이면 빈 포지션에서 시작)", example = "10")
        Integer initialHoldings,
        @Schema(description = "중간부터 시작 — 등록 시점 기존 평단가 (initialHoldings>0이면 필수)", example = "45.50")
        BigDecimal initialAvgPrice,
        // VR 전용 필드 (비VR 등록 시 null — @NotNull 없음, 서비스 검증)
        @Schema(description = "VR: 리밸런싱 주기 (주 단위, 1 이상)", example = "4")
        Integer intervalWeeks,
        @Schema(description = "VR: 매수·매도 사다리 밴드 폭 (%, 예: 15.00)", example = "15.00")
        BigDecimal bandWidth,
        @Schema(description = "VR: 주기당 추가 예수금 (USD, 음수=인출, 0=없음)", example = "0")
        Integer recurringAmount,
        // VR 램프 파라미터 (모두 생략 가능 — 생략 시 recurringAmount 부호 파생 기본값 + 52주 유예/26주 스텝 적용)
        @Schema(description = "VR: 램프 시작 시점 gradient(G) 값 (생략 시 인출식=20, 그 외=10)", example = "10")
        Integer initialGradient,
        @Schema(description = "VR: gradient 램프 시작 전 유예 주수 (생략 시 52)", example = "52")
        Integer gGraceWeeks,
        @Schema(description = "VR: gradient가 한 단계(1) 상승하는 주기 (생략 시 26)", example = "26")
        Integer gStepWeeks,
        @Schema(description = "VR: gradient 램프 상한값 (생략 시 initialGradient — 램프 없음)", example = "20")
        Integer gMax,
        @Schema(description = "VR: 램프 시작 시점 poolLimitRate 값 (생략 시 적립식=0.75/거치식=0.50/인출식=0.25)", example = "0.75")
        BigDecimal initialPoolLimitRate,
        @Schema(description = "VR: poolLimitRate 램프 시작 전 유예 주수 (생략 시 52)", example = "52")
        Integer pGraceWeeks,
        @Schema(description = "VR: poolLimitRate가 한 단계(5%p) 하강하는 주기 (생략 시 26)", example = "26")
        Integer pStepWeeks,
        @Schema(description = "VR: poolLimitRate 램프 하한값 (생략 시 initialPoolLimitRate — 램프 없음)", example = "0.50")
        BigDecimal poolLimitFloor,
        @Schema(description = "시작예정일, 기본값=오늘, 오늘 이후만 허용", example = "2026-08-01")
        LocalDate scheduledStartDate,
        @Schema(description = "VR: 초기 V값 직접 지정 (지정 시 전일종가×보유수량 계산을 대체, 생략 시 평가금 기준. 첫 매수 후 산정하려면 평가금·예수금과 함께 생략)", example = "5000.00")
        BigDecimal initialVrValue
) {
    public RegisterStrategyCommand toRegisterCommand() {
        return new RegisterStrategyCommand(type, ticker, initialUsdDeposit, cycleSeedType,
                divisionCount != null ? divisionCount : 0,
                initialHoldings, initialAvgPrice,
                intervalWeeks, bandWidth,
                recurringAmount != null ? recurringAmount : 0,
                initialGradient, gGraceWeeks, gStepWeeks, gMax,
                initialPoolLimitRate, pGraceWeeks, pStepWeeks, poolLimitFloor,
                scheduledStartDate, initialVrValue);
    }

    public UpdateStrategyCommand toUpdateCommand() {
        return new UpdateStrategyCommand(cycleSeedType, initialUsdDeposit);
    }
}
