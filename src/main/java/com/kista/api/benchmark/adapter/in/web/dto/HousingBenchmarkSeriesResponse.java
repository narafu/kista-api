package com.kista.api.benchmark.adapter.in.web.dto;

import org.jspecify.annotations.Nullable;
import com.kista.api.benchmark.domain.model.Cagr;
import com.kista.api.benchmark.domain.model.HousingBenchmarkPrice;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// 서울 아파트 5분위 매매평균가격 원본 시계열 (투자 성과와 무관)
public record HousingBenchmarkSeriesResponse(List<Point> points, @Nullable LocalDate sourceUpdatedDate, QuintileCagr quintileCagr) {

    // 분위별 연평균 상승률(비율, 0.05=5%) — 유효 구간이 1개월 미만이면 해당 분위는 null
    @Schema(name = "HousingBenchmarkQuintileCagr")
    public record QuintileCagr(
            @Nullable BigDecimal firstQuintile,
            @Nullable BigDecimal secondQuintile,
            @Nullable BigDecimal thirdQuintile,
            @Nullable BigDecimal fourthQuintile,
            @Nullable BigDecimal fifthQuintile) {}

    @Schema(name = "HousingBenchmarkSeriesPoint")
    public record Point(
            LocalDate baseMonth,
            BigDecimal firstQuintilePrice,
            BigDecimal secondQuintilePrice,
            BigDecimal thirdQuintilePrice,
            BigDecimal fourthQuintilePrice,
            BigDecimal fifthQuintilePrice) {}

    public static HousingBenchmarkSeriesResponse from(List<HousingBenchmarkPrice> prices) {
        List<Point> points = prices.stream()
                .map(p -> new Point(p.baseMonth(), p.firstQuintilePrice(), p.secondQuintilePrice(),
                        p.thirdQuintilePrice(), p.fourthQuintilePrice(), p.fifthQuintilePrice()))
                .toList();
        // 데이터 출처 최신 갱신일 — 여러 행 중 최댓값
        LocalDate sourceUpdatedDate = prices.stream()
                .map(HousingBenchmarkPrice::sourceUpdatedDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        // 분위별 CAGR — 분위마다 첫·끝 유효 시점이 다를 수 있어 따로 계산
        QuintileCagr cagr = new QuintileCagr(
                Cagr.byMonths(prices, HousingBenchmarkPrice::baseMonth, HousingBenchmarkPrice::firstQuintilePrice),
                Cagr.byMonths(prices, HousingBenchmarkPrice::baseMonth, HousingBenchmarkPrice::secondQuintilePrice),
                Cagr.byMonths(prices, HousingBenchmarkPrice::baseMonth, HousingBenchmarkPrice::thirdQuintilePrice),
                Cagr.byMonths(prices, HousingBenchmarkPrice::baseMonth, HousingBenchmarkPrice::fourthQuintilePrice),
                Cagr.byMonths(prices, HousingBenchmarkPrice::baseMonth, HousingBenchmarkPrice::fifthQuintilePrice));
        return new HousingBenchmarkSeriesResponse(points, sourceUpdatedDate, cagr);
    }
}
