package com.kista.api.benchmark.adapter.in.web.dto;

import org.jspecify.annotations.Nullable;
import com.kista.api.benchmark.domain.model.Cagr;
import com.kista.api.benchmark.domain.model.HousingPriceIndex;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

// 아파트 주간 매매가격지수 원본 시계열 (투자 성과와 무관)
public record HousingPriceIndexSeriesResponse(List<Point> points, @Nullable LocalDate sourceUpdatedDate,
                                              @Nullable BigDecimal cagr) {  // cagr: 조회 기간 연평균 상승률(비율), 유효 구간 1일 미만이면 null

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
        // 첫·마지막 유효 지수 기준 CAGR
        BigDecimal cagr = Cagr.byDays(indices, HousingPriceIndex::baseDate, HousingPriceIndex::indexValue);
        return new HousingPriceIndexSeriesResponse(points, sourceUpdatedDate, cagr);
    }
}
