package com.kista.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kista.domain.model.stats.BenchmarkAssetType;
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
        StrategyInfo strategy,
        Benchmark benchmark,
        Period period,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        Summary summary,
        List<Point> points,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        CurrentExchangeRate currentExchangeRate,
        Quality quality,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @Schema(types = {"string", "null"}) String emptyReason
) {
    private static final String HOUSING_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자 성과는 USD, 서울 아파트는 KRW 현지 통화 기준이며 현재 환율은 성과 계산에 반영하지 않습니다.";
    private static final String ETF_NOTICE =
            "전략 운용 기록 기반 근사치입니다. 투자와 ETF 벤치마크 모두 USD 기준이며 환율 변수가 없습니다.";

    @Schema(name = "HousingBenchmarkStrategyInfo")
    public record StrategyInfo(UUID id, String type, String ticker) {}

    @Schema(name = "HousingBenchmarkDefinition")
    public record Benchmark(
            String assetType,
            @Schema(types = {"string", "null"}) String regionCode,
            @Schema(types = {"string", "null"}) String regionName,
            @Schema(types = {"integer", "null"}) Integer quintile,
            @Schema(types = {"string", "null"}) String symbol,
            String label,
            @Schema(types = {"string", "null"}, format = "date") LocalDate sourceUpdatedDate
    ) {}

    @Schema(name = "HousingBenchmarkPeriod")
    public record Period(
            @Schema(types = {"string", "null"}, format = "date") LocalDate fromDate,
            @Schema(types = {"string", "null"}, format = "date") LocalDate toDate,
            int pointCount
    ) {}

    @Schema(name = "HousingBenchmarkSummary")
    public record Summary(
            BigDecimal investmentCumulativeReturn,
            BigDecimal benchmarkCumulativeReturn,
            BigDecimal excessReturn,
            BigDecimal investmentAnnualizedReturn,
            BigDecimal benchmarkAnnualizedReturn,
            BigDecimal investmentMaxDrawdown,
            BigDecimal benchmarkMaxDrawdown
    ) {}

    @Schema(name = "HousingBenchmarkPoint")
    public record Point(
            LocalDate baseDate,
            BigDecimal investmentIndexUsd,
            BigDecimal benchmarkIndex,
            @Schema(types = {"number", "null"}) BigDecimal investmentPeriodReturn,
            @Schema(types = {"number", "null"}) BigDecimal benchmarkPeriodReturn
    ) {}

    @Schema(name = "HousingBenchmarkCurrentExchangeRate")
    public record CurrentExchangeRate(
            BigDecimal midRate,
            Instant fetchedAt,
            @Schema(allowableValues = "TOSS_INVEST") String source
    ) {}

    @Schema(name = "HousingBenchmarkQuality")
    public record Quality(
            String method, String investmentCurrency, String benchmarkCurrency, String notice
    ) {}

    public static HousingBenchmarkComparisonResponse from(HousingBenchmarkComparison comparison) {
        HousingBenchmarkComparison.StrategyInfo strategy = comparison.strategy();
        HousingBenchmarkComparison.Benchmark benchmark = comparison.benchmark();
        HousingBenchmarkComparison.Period period = comparison.period();
        var rate = comparison.currentExchangeRate();
        boolean isEtf = benchmark.assetType() == BenchmarkAssetType.ETF;
        return new HousingBenchmarkComparisonResponse(
                comparison.scope().name(),
                strategy == null ? null : new StrategyInfo(
                        strategy.id(), strategy.type().name(), strategy.ticker().name()),
                new Benchmark(
                        benchmark.assetType().name(), benchmark.regionCode(), benchmark.regionName(),
                        benchmark.quintile(), benchmark.symbol(),
                        benchmark.label(), benchmark.sourceUpdatedDate()),
                new Period(period.fromDate(), period.toDate(), period.pointCount()),
                toSummary(comparison.summary()),
                comparison.points().stream().map(HousingBenchmarkComparisonResponse::toPoint).toList(),
                rate == null ? null : new CurrentExchangeRate(
                        rate.midRate(), rate.fetchedAt(), rate.source()),
                isEtf
                        ? new Quality("ESTIMATED_TIME_WEIGHTED_RETURN", "USD", "USD", ETF_NOTICE)
                        : new Quality("ESTIMATED_TIME_WEIGHTED_RETURN", "USD", "KRW", HOUSING_NOTICE),
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
                point.baseDate(), point.investmentIndexUsd(), point.benchmarkIndex(),
                point.investmentPeriodReturn(), point.benchmarkPeriodReturn());
    }
}
