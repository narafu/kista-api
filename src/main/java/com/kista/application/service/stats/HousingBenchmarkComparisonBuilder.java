package com.kista.application.service.stats;

import com.kista.domain.model.stats.BenchmarkGranularity;
import com.kista.domain.model.stats.BenchmarkScope;
import com.kista.domain.model.stats.HousingBenchmarkComparison;
import com.kista.domain.model.stats.HousingBenchmarkPoint;
import com.kista.domain.model.stats.InvestmentPoint;
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
    private static final double DAYS_PER_YEAR = 365.0;

    HousingBenchmarkComparison build(
            BenchmarkScope scope,
            Strategy strategy,
            HousingBenchmarkComparison.Benchmark benchmark,
            List<InvestmentPoint> investmentPoints,
            Map<LocalDate, BigDecimal> benchmarkPrices,
            BenchmarkGranularity granularity) {
        HousingBenchmarkComparison.StrategyInfo strategyInfo = strategy == null ? null
                : new HousingBenchmarkComparison.StrategyInfo(
                        strategy.id(), strategy.type(), strategy.ticker());

        if (investmentPoints.isEmpty()) {
            return empty(scope, strategyInfo, benchmark, "NO_INVESTMENT_DATA");
        }

        Map<LocalDate, InvestmentPoint> investmentByDate = investmentPoints.stream()
                .collect(Collectors.toMap(
                        InvestmentPoint::baseDate, Function.identity(), (left, right) -> right));
        TreeSet<LocalDate> commonDates = new TreeSet<>(investmentByDate.keySet());
        commonDates.retainAll(benchmarkPrices.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().signum() > 0)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet()));

        if (commonDates.size() < 2) {
            return empty(scope, strategyInfo, benchmark, "INSUFFICIENT_COMMON_MONTHS");
        }

        LocalDate firstDate = commonDates.getFirst();
        BigDecimal firstInvestmentIndex = investmentByDate.get(firstDate).investmentIndexUsd();
        BigDecimal firstBenchmarkPrice = benchmarkPrices.get(firstDate);
        if (firstInvestmentIndex == null || firstInvestmentIndex.signum() <= 0) {
            return empty(scope, strategyInfo, benchmark, "INSUFFICIENT_COMMON_MONTHS");
        }

        List<HousingBenchmarkPoint> points = new ArrayList<>();
        LocalDate previousDate = null;
        BigDecimal previousInvestmentIndex = null;
        BigDecimal previousBenchmarkIndex = null;
        for (LocalDate date : commonDates) {
            BigDecimal investmentIndex = normalize(
                    investmentByDate.get(date).investmentIndexUsd(), firstInvestmentIndex);
            BigDecimal benchmarkIndex = normalize(benchmarkPrices.get(date), firstBenchmarkPrice);
            // MONTHLY는 인접 데이터 간 공백(결측월)이 있으면 수익률을 비워 오해를 막는다.
            // DAILY는 commonDates 자체가 이미 실거래일 교집합이라 인접 검사 없이 항상 계산한다.
            boolean computeReturn = previousDate != null
                    && (granularity == BenchmarkGranularity.DAILY || date.equals(previousDate.plusMonths(1)));
            points.add(new HousingBenchmarkPoint(
                    date,
                    investmentIndex,
                    benchmarkIndex,
                    computeReturn ? periodReturn(investmentIndex, previousInvestmentIndex) : null,
                    computeReturn ? periodReturn(benchmarkIndex, previousBenchmarkIndex) : null));
            previousDate = date;
            previousInvestmentIndex = investmentIndex;
            previousBenchmarkIndex = benchmarkIndex;
        }

        BigDecimal lastInvestmentIndex = points.getLast().investmentIndexUsd();
        BigDecimal lastBenchmarkIndex = points.getLast().benchmarkIndex();
        BigDecimal investmentCumulativeReturn = cumulativeReturn(lastInvestmentIndex);
        BigDecimal benchmarkCumulativeReturn = cumulativeReturn(lastBenchmarkIndex);
        double periodsPerYear = granularity == BenchmarkGranularity.DAILY
                ? DAYS_PER_YEAR / ChronoUnit.DAYS.between(firstDate, points.getLast().baseDate())
                : 12.0 / ChronoUnit.MONTHS.between(firstDate, points.getLast().baseDate());
        PerformanceComparisonSummary summary = new PerformanceComparisonSummary(
                investmentCumulativeReturn,
                benchmarkCumulativeReturn,
                investmentCumulativeReturn.subtract(benchmarkCumulativeReturn).setScale(SCALE, HALF_UP),
                annualizedReturn(lastInvestmentIndex, periodsPerYear),
                annualizedReturn(lastBenchmarkIndex, periodsPerYear),
                maxDrawdown(points.stream().map(HousingBenchmarkPoint::investmentIndexUsd).toList()),
                maxDrawdown(points.stream().map(HousingBenchmarkPoint::benchmarkIndex).toList()));

        return new HousingBenchmarkComparison(
                scope,
                strategyInfo,
                benchmark,
                new HousingBenchmarkComparison.Period(
                        firstDate, points.getLast().baseDate(), points.size()),
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

    private static BigDecimal periodReturn(BigDecimal current, BigDecimal previous) {
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

    private static BigDecimal annualizedReturn(BigDecimal lastIndex, double periodsPerYear) {
        if (lastIndex.signum() <= 0) {
            // 지수가 0 이하(전액 손실)면 Math.pow가 NaN을 낼 수 있어 -100%로 확정
            return BigDecimal.ONE.negate().setScale(SCALE, HALF_UP);
        }
        double annualized = Math.pow(
                lastIndex.divide(HUNDRED, SCALE, HALF_UP).doubleValue(),
                periodsPerYear) - 1.0;
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
