package com.kista.stats.adapter.in.web.dto;

import com.kista.stats.domain.model.HousingPriceIndex;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// 아파트 주간 매매가격지수 원본 시계열 (투자 성과와 무관)
public record HousingPriceIndexSeriesResponse(List<Point> points, LocalDate sourceUpdatedDate) {

    @Schema(name = "HousingPriceIndexSeriesPoint")
    public record Point(LocalDate baseDate, BigDecimal indexValue) {}

    public static HousingPriceIndexSeriesResponse from(List<HousingPriceIndex> indices) {
        List<Point> points = indices.stream()
                .map(i -> new Point(i.baseDate(), i.indexValue()))
                .toList();
        // 데이터 출처 최신 갱신일 — 여러 행 중 최댓값
        LocalDate sourceUpdatedDate = indices.stream()
                .map(HousingPriceIndex::sourceUpdatedDate)
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);
        return new HousingPriceIndexSeriesResponse(points, sourceUpdatedDate);
    }
}
