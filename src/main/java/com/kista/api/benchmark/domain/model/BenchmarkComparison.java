package com.kista.api.benchmark.domain.model;

import com.kista.shared.kernel.Currency;

import java.time.LocalDate;
import java.util.List;

public record BenchmarkComparison(
        BenchmarkScope scope,
        StrategyRef strategy,
        Currency investmentCurrency, // 투자 통화 — STRATEGY=전략 계좌 통화, PORTFOLIO=요청 통화
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
                scope, strategy, investmentCurrency, benchmark, period, summary, points, rate, emptyReason);
    }
}
