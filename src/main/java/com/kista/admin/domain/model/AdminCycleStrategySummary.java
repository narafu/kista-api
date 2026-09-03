package com.kista.admin.domain.model;

import com.kista.domain.model.strategy.Strategy;

import java.util.UUID;
import com.kista.sharedkernel.StrategyType;

public record AdminCycleStrategySummary(
        UUID strategyId,
        StrategyType strategyType
) {
}
