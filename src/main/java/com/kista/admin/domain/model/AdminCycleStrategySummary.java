package com.kista.admin.domain.model;

import com.kista.domain.model.strategy.Strategy;

import java.util.UUID;

public record AdminCycleStrategySummary(
        UUID strategyId,
        Strategy.Type strategyType
) {
}
