package com.kista.domain.model.strategy;

import java.math.BigDecimal;

// Strategy + 현재 StrategyCycle의 initialUsdDeposit — API 응답 조립용 (TradingCycleResponse)
public record StrategyDetail(Strategy strategy, BigDecimal initialUsdDeposit) {}
