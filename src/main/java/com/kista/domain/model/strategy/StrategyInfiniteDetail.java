package com.kista.domain.model.strategy;

import java.util.UUID;

public record StrategyInfiniteDetail(
        UUID strategyId,
        int divisionCount
) {}
