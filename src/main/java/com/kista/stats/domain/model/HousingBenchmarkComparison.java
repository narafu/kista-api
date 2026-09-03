package com.kista.stats.domain.model;

import com.kista.strategyconfig.domain.model.Strategy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyTicker;

public record HousingBenchmarkComparison(
        BenchmarkScope scope,
        StrategyInfo strategy,
        Benchmark benchmark,
        Period period,
        PerformanceComparisonSummary summary,
        List<HousingBenchmarkPoint> points,
        CurrentExchangeRate currentExchangeRate,
        String emptyReason
) {
    public record StrategyInfo(UUID id, StrategyType type, StrategyTicker ticker) {}

    public record Benchmark(
            BenchmarkAssetType assetType,
            String regionCode,   // HOUSING 전용, ETF면 null
            String regionName,   // HOUSING 전용, ETF면 null
            String symbol,       // ETF 전용, HOUSING이면 null
            String label,
            LocalDate sourceUpdatedDate
    ) {}

    public record Period(LocalDate fromDate, LocalDate toDate, int pointCount) {}

    public HousingBenchmarkComparison withCurrentExchangeRate(CurrentExchangeRate rate) {
        return new HousingBenchmarkComparison(
                scope, strategy, benchmark, period, summary, points, rate, emptyReason);
    }
}
