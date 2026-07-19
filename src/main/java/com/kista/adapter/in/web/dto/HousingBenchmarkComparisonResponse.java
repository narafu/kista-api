package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kista.domain.model.stats.HousingBenchmarkComparison;
import com.kista.domain.model.stats.HousingBenchmarkPoint;
import com.kista.domain.model.stats.PerformanceComparisonSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record HousingBenchmarkComparisonResponse(
        String scope,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(types = {"object", "null"}) StrategyInfo strategy,
        Benchmark benchmark,
        Period period,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(types = {"object", "null"}) Summary summary,
        List<Point> points,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(types = {"object", "null"}) CurrentExchangeRate currentExchangeRate,
        Quality quality,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(types = {"string", "null"}) String emptyReason
) {
    private static final String NOTICE =
            "투자 성과는 USD, 서울 아파트는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다.";

    public record StrategyInfo(UUID id, String type, String ticker) {}

    public record Benchmark(
            String regionCode, String regionName, int quintile,
            String label,
            @Schema(types = {"string", "null"}, format = "date") LocalDate sourceUpdatedDate
    ) {}

    public record Period(
            @Schema(types = {"string", "null"}, format = "date") LocalDate fromMonth,
            @Schema(types = {"string", "null"}, format = "date") LocalDate toMonth,
            int monthCount
    ) {}

    public record Summary(
            BigDecimal investmentCumulativeReturn,
            BigDecimal benchmarkCumulativeReturn,
            BigDecimal excessReturn,
            BigDecimal investmentAnnualizedReturn,
            BigDecimal benchmarkAnnualizedReturn,
            BigDecimal investmentMaxDrawdown,
            BigDecimal benchmarkMaxDrawdown
    ) {}

    public record Point(
            LocalDate baseMonth,
            BigDecimal investmentIndexUsd,
            BigDecimal benchmarkIndex,
            @Schema(types = {"number", "null"}) BigDecimal investmentMonthlyReturn,
            @Schema(types = {"number", "null"}) BigDecimal benchmarkMonthlyReturn
    ) {}

    public record CurrentExchangeRate(BigDecimal midRate, Instant fetchedAt, String source) {}

    public record Quality(
            String method, String investmentCurrency, String benchmarkCurrency, String notice
    ) {}

    public static HousingBenchmarkComparisonResponse from(HousingBenchmarkComparison comparison) {
        HousingBenchmarkComparison.StrategyInfo strategy = comparison.strategy();
        HousingBenchmarkComparison.Benchmark benchmark = comparison.benchmark();
        HousingBenchmarkComparison.Period period = comparison.period();
        var rate = comparison.currentExchangeRate();
        return new HousingBenchmarkComparisonResponse(
                comparison.scope().name(),
                strategy == null ? null : new StrategyInfo(
                        strategy.id(), strategy.type().name(), strategy.ticker().name()),
                new Benchmark(
                        benchmark.regionCode(), benchmark.regionName(), benchmark.quintile(),
                        benchmark.label(), benchmark.sourceUpdatedDate()),
                new Period(period.fromMonth(), period.toMonth(), period.monthCount()),
                toSummary(comparison.summary()),
                comparison.points().stream().map(HousingBenchmarkComparisonResponse::toPoint).toList(),
                rate == null ? null : new CurrentExchangeRate(
                        rate.midRate(), rate.fetchedAt(), rate.source()),
                new Quality("ESTIMATED_TIME_WEIGHTED_RETURN", "USD", "KRW", NOTICE),
                comparison.emptyReason());
    }

    private static Summary toSummary(PerformanceComparisonSummary summary) {
        return summary == null ? null : new Summary(
                summary.investmentCumulativeReturn(), summary.benchmarkCumulativeReturn(),
                summary.excessReturn(), summary.investmentAnnualizedReturn(),
                summary.benchmarkAnnualizedReturn(), summary.investmentMaxDrawdown(),
                summary.benchmarkMaxDrawdown());
    }

    private static Point toPoint(HousingBenchmarkPoint point) {
        return new Point(
                point.baseMonth(), point.investmentIndexUsd(), point.benchmarkIndex(),
                point.investmentMonthlyReturn(), point.benchmarkMonthlyReturn());
    }
}
