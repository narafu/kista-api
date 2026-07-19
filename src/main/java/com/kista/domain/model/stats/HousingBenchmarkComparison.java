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
            String regionCode,
            String regionName,
            int quintile,
            String label,
            LocalDate sourceUpdatedDate
    ) {}

    public record Period(LocalDate fromMonth, LocalDate toMonth, int monthCount) {}

    public HousingBenchmarkComparison withCurrentExchangeRate(CurrentExchangeRate rate) {
        return new HousingBenchmarkComparison(
                scope, strategy, benchmark, period, summary, points, rate, emptyReason);
    }
}
