package com.kista.api.benchmark.adapter.in.web.dto;

import com.kista.api.benchmark.domain.model.Cagr;
import com.kista.api.benchmark.domain.model.IndexPrice;
import org.jspecify.annotations.Nullable;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// ETF 원본 일별 종가 시계열 (투자 성과와 무관)
public record EtfPriceSeriesResponse(List<Point> points,
                                  @Nullable BigDecimal cagr) {  // cagr: 조회 기간 연평균 상승률(비율), 유효 구간 1일 미만이면 null

    @Schema(name = "EtfPriceSeriesPoint")
    public record Point(LocalDate tradeDate, BigDecimal close) {}

    public static EtfPriceSeriesResponse from(List<IndexPrice> prices) {
        List<Point> points = prices.stream()
                .map(p -> new Point(p.tradeDate(), p.close()))
                .toList();
        // 첫·마지막 유효 종가 기준 CAGR
        return new EtfPriceSeriesResponse(points, Cagr.byDays(prices, IndexPrice::tradeDate, IndexPrice::close));
    }
}
