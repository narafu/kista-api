package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// Strategy + 현재 StrategyCycle 상태 — API 응답 조립용 (TradingCycleResponse)
public record StrategyDetail(
        Strategy strategy,
        BigDecimal initialUsdDeposit,
        Integer divisionCount,
        boolean isReverseMode,
        Double currentRound    // INFINITE 전략만 non-null, 이력 없으면 null
) {}
