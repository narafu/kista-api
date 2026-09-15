package com.kista.stats.application.port.output;

import com.kista.stats.domain.model.BenchmarkGranularity;
import com.kista.stats.domain.model.BenchmarkScope;
import com.kista.stats.domain.model.InvestmentPoint;
import com.kista.stats.domain.model.StrategyRef;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvestmentPointsPort {
    record Result(List<InvestmentPoint> points, LocalDate effectiveFrom, LocalDate effectiveTo, StrategyRef selectedStrategy) {}

    Result fetch(UUID userId, BenchmarkScope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity);
}
