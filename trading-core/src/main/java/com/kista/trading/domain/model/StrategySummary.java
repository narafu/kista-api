package com.kista.trading.domain.model;

import java.util.UUID;
import com.kista.sharedkernel.StrategyType;

// strategy_cycle.id → strategyId + strategy.type 배치 조회 결과 — admin이 이 타입을 직접 소비(own-type 아님)
public record StrategySummary(
        UUID strategyId,
        StrategyType strategyType
) {
}
