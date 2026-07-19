package com.kista.adapter.in.web.dto;

import com.kista.domain.model.stats.HousingBenchmarkPrice;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// 서울 아파트 5분위 매매평균가격 원본 시계열 (투자 성과와 무관)
public record HousingBenchmarkSeriesResponse(List<Point> points, LocalDate sourceUpdatedDate) {

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
        return new HousingBenchmarkSeriesResponse(points, sourceUpdatedDate);
    }
}
