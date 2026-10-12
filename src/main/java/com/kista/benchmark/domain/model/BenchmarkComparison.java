package com.kista.benchmark.domain.model;

import java.time.LocalDate;
import java.util.List;

public record BenchmarkComparison(
        BenchmarkScope scope,
        StrategyRef strategy,
        Benchmark benchmark,
        Period period,
        PerformanceComparisonSummary summary,
        List<BenchmarkPoint> points,
        CurrentExchangeRate currentExchangeRate,
        String emptyReason
) {
    public record Benchmark(
            BenchmarkAssetType assetType,
            String regionCode,   // HOUSING 전용, ETF면 null
            String regionName,   // HOUSING 전용, ETF면 null
            String symbol,       // ETF 전용, HOUSING이면 null
            String label,
            LocalDate sourceUpdatedDate
    ) {}

    public record Period(LocalDate fromDate, LocalDate toDate, int pointCount) {}

    public BenchmarkComparison withCurrentExchangeRate(CurrentExchangeRate rate) {
        return new BenchmarkComparison(
                scope, strategy, benchmark, period, summary, points, rate, emptyReason);
    }
}
