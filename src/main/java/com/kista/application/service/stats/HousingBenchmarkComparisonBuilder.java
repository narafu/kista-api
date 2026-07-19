package com.kista.application.service.stats;

import com.kista.domain.model.stats.BenchmarkScope;
import com.kista.domain.model.stats.HousingBenchmarkComparison;
import com.kista.domain.model.stats.HousingBenchmarkPoint;
import com.kista.domain.model.stats.MonthlyInvestmentPoint;
import com.kista.domain.model.stats.PerformanceComparisonSummary;
import com.kista.domain.model.strategy.Strategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

final class HousingBenchmarkComparisonBuilder {

    private static final int SCALE = 10;
    private static final RoundingMode HALF_UP = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    HousingBenchmarkComparison build(
            BenchmarkScope scope,
            Strategy strategy,
            int quintile,
            String regionCode,
            String regionName,
            LocalDate sourceUpdatedDate,
            List<MonthlyInvestmentPoint> investmentPoints,
            Map<LocalDate, BigDecimal> benchmarkPrices) {
        HousingBenchmarkComparison.Benchmark benchmark = new HousingBenchmarkComparison.Benchmark(
                regionCode, regionName, quintile, regionName + " 아파트 " + quintile + "분위",
                sourceUpdatedDate);
        HousingBenchmarkComparison.StrategyInfo strategyInfo = strategy == null ? null
                : new HousingBenchmarkComparison.StrategyInfo(
                        strategy.id(), strategy.type(), strategy.ticker());

        if (investmentPoints.isEmpty()) {
            return empty(scope, strategyInfo, benchmark, "NO_INVESTMENT_DATA");
        }

        Map<LocalDate, MonthlyInvestmentPoint> investmentByMonth = investmentPoints.stream()
                .collect(Collectors.toMap(
                        MonthlyInvestmentPoint::baseMonth, Function.identity(), (left, right) -> right));
        TreeSet<LocalDate> commonMonths = new TreeSet<>(investmentByMonth.keySet());
        commonMonths.retainAll(benchmarkPrices.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().signum() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet()));

        if (commonMonths.size() < 2) {
            return empty(scope, strategyInfo, benchmark, "INSUFFICIENT_COMMON_MONTHS");
        }

        LocalDate firstMonth = commonMonths.getFirst();
        BigDecimal firstInvestmentIndex = investmentByMonth.get(firstMonth).investmentIndexUsd();
        BigDecimal firstBenchmarkPrice = benchmarkPrices.get(firstMonth);
        if (firstInvestmentIndex == null || firstInvestmentIndex.signum() <= 0) {
            return empty(scope, strategyInfo, benchmark, "INSUFFICIENT_COMMON_MONTHS");
        }

        List<HousingBenchmarkPoint> points = new ArrayList<>();
        BigDecimal previousInvestmentIndex = null;
        BigDecimal previousBenchmarkIndex = null;
        for (LocalDate month : commonMonths) {
            BigDecimal investmentIndex = normalize(
                    investmentByMonth.get(month).investmentIndexUsd(), firstInvestmentIndex);
            BigDecimal benchmarkIndex = normalize(benchmarkPrices.get(month), firstBenchmarkPrice);
            points.add(new HousingBenchmarkPoint(
                    month,
                    investmentIndex,
                    benchmarkIndex,
                    monthlyReturn(investmentIndex, previousInvestmentIndex),
                    monthlyReturn(benchmarkIndex, previousBenchmarkIndex)));
            previousInvestmentIndex = investmentIndex;
            previousBenchmarkIndex = benchmarkIndex;
        }

        BigDecimal lastInvestmentIndex = points.getLast().investmentIndexUsd();
        BigDecimal lastBenchmarkIndex = points.getLast().benchmarkIndex();
        BigDecimal investmentCumulativeReturn = cumulativeReturn(lastInvestmentIndex);
        BigDecimal benchmarkCumulativeReturn = cumulativeReturn(lastBenchmarkIndex);
        long elapsedMonths = ChronoUnit.MONTHS.between(firstMonth, points.getLast().baseMonth());
        PerformanceComparisonSummary summary = new PerformanceComparisonSummary(
                investmentCumulativeReturn,
                benchmarkCumulativeReturn,
                investmentCumulativeReturn.subtract(benchmarkCumulativeReturn).setScale(SCALE, HALF_UP),
                annualizedReturn(lastInvestmentIndex, elapsedMonths),
                annualizedReturn(lastBenchmarkIndex, elapsedMonths),
                maxDrawdown(points.stream().map(HousingBenchmarkPoint::investmentIndexUsd).toList()),
                maxDrawdown(points.stream().map(HousingBenchmarkPoint::benchmarkIndex).toList()));

        return new HousingBenchmarkComparison(
                scope,
                strategyInfo,
                benchmark,
                new HousingBenchmarkComparison.Period(
                        firstMonth, points.getLast().baseMonth(), points.size()),
                summary,
                List.copyOf(points),
                null,
                null);
    }

    private static HousingBenchmarkComparison empty(
            BenchmarkScope scope,
            HousingBenchmarkComparison.StrategyInfo strategy,
            HousingBenchmarkComparison.Benchmark benchmark,
            String reason) {
        return new HousingBenchmarkComparison(
                scope, strategy, benchmark,
                new HousingBenchmarkComparison.Period(null, null, 0),
                null, List.of(), null, reason);
    }

    private static BigDecimal normalize(BigDecimal value, BigDecimal initialValue) {
        return value.divide(initialValue, SCALE, HALF_UP)
                .multiply(HUNDRED)
                .setScale(SCALE, HALF_UP);
    }

    private static BigDecimal monthlyReturn(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() <= 0) {
            return null;
        }
        return current.divide(previous, SCALE, HALF_UP)
                .subtract(BigDecimal.ONE)
                .setScale(SCALE, HALF_UP);
    }

    private static BigDecimal cumulativeReturn(BigDecimal lastIndex) {
        return lastIndex.divide(HUNDRED, SCALE, HALF_UP)
                .subtract(BigDecimal.ONE)
                .setScale(SCALE, HALF_UP);
    }

    private static BigDecimal annualizedReturn(BigDecimal lastIndex, long elapsedMonths) {
        double annualized = Math.pow(
                lastIndex.divide(HUNDRED, SCALE, HALF_UP).doubleValue(),
                12.0 / elapsedMonths) - 1.0;
        return BigDecimal.valueOf(annualized).setScale(SCALE, HALF_UP);
    }

    private static BigDecimal maxDrawdown(List<BigDecimal> indices) {
        BigDecimal peak = null;
        BigDecimal maxDrawdown = BigDecimal.ZERO.setScale(SCALE, HALF_UP);
        for (BigDecimal index : indices) {
            if (peak == null || index.compareTo(peak) > 0) {
                peak = index;
            }
            if (peak.signum() <= 0) {
                continue;
            }
            BigDecimal drawdown = index.divide(peak, SCALE, HALF_UP)
                    .subtract(BigDecimal.ONE)
                    .setScale(SCALE, HALF_UP);
            if (drawdown.compareTo(maxDrawdown) < 0) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown;
    }
}
