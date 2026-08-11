package com.kista.application.service.stats;

import com.kista.domain.model.stats.BenchmarkAssetType;
import com.kista.domain.model.stats.BenchmarkGranularity;
import com.kista.domain.model.stats.BenchmarkScope;
import com.kista.domain.model.stats.HousingBenchmarkComparison;
import com.kista.domain.model.stats.InvestmentPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class HousingBenchmarkComparisonBuilderTest {

    private final HousingBenchmarkComparisonBuilder builder = new HousingBenchmarkComparisonBuilder();

    private static final HousingBenchmarkComparison.Benchmark BENCHMARK = new HousingBenchmarkComparison.Benchmark(
            BenchmarkAssetType.HOUSING, "1100000000", "서울", null, null, "서울 아파트 매매가격지수", null);

    private static InvestmentPoint point(LocalDate date, String indexUsd) {
        return new InvestmentPoint(date, new BigDecimal(indexUsd), null);
    }

    private static Map<LocalDate, BigDecimal> prices(LocalDate d1, String v1, LocalDate d2, String v2) {
        Map<LocalDate, BigDecimal> map = new TreeMap<>();
        map.put(d1, new BigDecimal(v1));
        map.put(d2, new BigDecimal(v2));
        return map;
    }

    @Test
    void WEEKLY_구간은_KB_결측_주가_있어도_인접_공통_포인트마다_수익률을_계산한다() {
        // 1/5 -> 2/16 (결측 주 존재, 정확히 1개월 뒤가 아님)까지도 항상 수익률이 나와야 한다
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 2, 16);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "110"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "121");

        HousingBenchmarkComparison result = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.WEEKLY);

        assertThat(result.points().getLast().investmentPeriodReturn()).isEqualByComparingTo("0.1");
        assertThat(result.points().getLast().benchmarkPeriodReturn()).isEqualByComparingTo("0.21");
    }

    @Test
    void WEEKLY_3주_구간에서는_예외_없이_결과를_반환한다() {
        // 과거 MONTHS.between==0으로 Infinity 예외가 나던 케이스의 회귀 방지
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 1, 26);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "101"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "102");

        HousingBenchmarkComparison result = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.WEEKLY);

        assertThat(result.emptyReason()).isNull();
        assertThat(result.summary()).isNotNull();
    }

    @Test
    void 짧은_구간에서는_연환산_수익률을_null로_억제한다() {
        // 21일(< 90일) 구간 — WEEKLY(아파트)와 DAILY(ETF) 둘 다 동일 규칙 적용
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 1, 26);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "101"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "102");

        HousingBenchmarkComparison weekly = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.WEEKLY);
        HousingBenchmarkComparison daily = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.DAILY);

        assertThat(weekly.summary().investmentAnnualizedReturn()).isNull();
        assertThat(weekly.summary().benchmarkAnnualizedReturn()).isNull();
        assertThat(daily.summary().investmentAnnualizedReturn()).isNull();
        assertThat(daily.summary().benchmarkAnnualizedReturn()).isNull();
    }

    @Test
    void 긴_구간에서는_연환산_수익률을_계산한다() {
        // 119일(>= 90일) 구간
        LocalDate d1 = LocalDate.of(2026, 1, 5);
        LocalDate d2 = LocalDate.of(2026, 5, 4);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "184.2"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "400");

        HousingBenchmarkComparison result = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.WEEKLY);

        assertThat(result.summary().investmentAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(1.842, 365.0 / 119.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
    }

    @Test
    void MONTHLY_구간에서는_짧은_구간이어도_연환산_수익률을_그대로_계산한다() {
        // MONTHLY는 이번 변경의 영향을 받지 않아야 한다 (회귀 방지)
        LocalDate d1 = LocalDate.of(2026, 1, 1);
        LocalDate d2 = LocalDate.of(2026, 2, 1);
        List<InvestmentPoint> investmentPoints = List.of(point(d1, "100"), point(d2, "184.2"));
        Map<LocalDate, BigDecimal> benchmarkPrices = prices(d1, "100", d2, "400");

        HousingBenchmarkComparison result = builder.build(
                BenchmarkScope.PORTFOLIO, null, BENCHMARK, investmentPoints, benchmarkPrices,
                BenchmarkGranularity.MONTHLY);

        assertThat(result.summary().investmentAnnualizedReturn()).isCloseTo(
                BigDecimal.valueOf(Math.pow(1.842, 12.0) - 1.0),
                org.assertj.core.data.Offset.offset(new BigDecimal("0.000000001")));
    }
}
