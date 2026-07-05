package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// Strategy + 현재 StrategyCycle 상태 — API 응답 조립용 (TradingCycleResponse)
public record StrategyDetail(
        Strategy strategy,
        BigDecimal initialUsdDeposit,
        Integer divisionCount,
        boolean isReverseMode,
        Double currentRound,    // INFINITE 전략만 non-null, 이력 없으면 null
        Integer currentHoldings, // 최신 cycle_position 기준 보유 수량
        VrSummary vr            // VR 전략만 non-null, 비VR은 null
) {

    // VR 전략 조회 응답 요약 — StrategyVrDetail + StrategyCycleVrDetail 합산
    public record VrSummary(
            BigDecimal value,        // 사이클 시작 시 V값 (실력 기준선)
            BigDecimal bandWidth,    // 밴드 폭 (%, 예: 15.00)
            int intervalWeeks,       // 리밸런싱 주기 (주 단위)
            int recurringAmount,     // 주기당 추가 예수금 (USD, 음수=인출)
            BigDecimal poolLimit,    // 사이클 pool 상한 금액 (USD)
            int gradient             // 실력공식 경사 계수 (G)
    ) {}
}
