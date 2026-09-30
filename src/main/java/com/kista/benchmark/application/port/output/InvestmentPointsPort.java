package com.kista.benchmark.application.port.output;

import com.kista.sharedkernel.BenchmarkGranularity;
import com.kista.benchmark.domain.model.BenchmarkScope;
import com.kista.benchmark.domain.model.InvestmentPoint;
import com.kista.benchmark.domain.model.StrategyRef;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface InvestmentPointsPort {
    record Result(List<InvestmentPoint> points, LocalDate effectiveFrom, LocalDate effectiveTo, StrategyRef selectedStrategy) {}

    Result fetch(UUID userId, BenchmarkScope scope, UUID strategyId, LocalDate from, LocalDate to, BenchmarkGranularity granularity);
}
