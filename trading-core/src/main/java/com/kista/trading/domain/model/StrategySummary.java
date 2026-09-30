package com.kista.trading.domain.model;

import java.util.UUID;
import com.kista.sharedkernel.StrategyType;

// strategy_cycle.id → strategyId + strategy.type 배치 조회 결과 — 내부 API wire는 contract.trading.StrategySummaryResponse(TradingContractMapper가 매핑)
public record StrategySummary(
        UUID strategyId,
        StrategyType strategyType
) {
}
