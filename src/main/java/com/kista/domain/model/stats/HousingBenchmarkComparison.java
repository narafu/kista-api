package com.kista.domain.model.stats;

import com.kista.domain.model.strategy.Strategy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
    public record StrategyInfo(UUID id, Strategy.Type type, Strategy.Ticker ticker) {}

    public record Benchmark(
            BenchmarkAssetType assetType,
            String regionCode,   // HOUSING 전용, ETF면 null
            String regionName,   // HOUSING 전용, ETF면 null
            Integer quintile,    // HOUSING 전용, ETF면 null
            String symbol,       // ETF 전용, HOUSING이면 null
            String label,
            LocalDate sourceUpdatedDate
    ) {}

    public record Period(LocalDate fromMonth, LocalDate toMonth, int monthCount) {}

    public HousingBenchmarkComparison withCurrentExchangeRate(CurrentExchangeRate rate) {
        return new HousingBenchmarkComparison(
                scope, strategy, benchmark, period, summary, points, rate, emptyReason);
    }
}
