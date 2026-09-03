package com.kista.stats.adapter.in.web.dto;

import com.kista.stats.domain.model.IndexPrice;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

// ETF 원본 일별 종가 시계열 (투자 성과와 무관)
public record EtfPriceSeriesResponse(List<Point> points) {

    @Schema(name = "EtfPriceSeriesPoint")
    public record Point(LocalDate tradeDate, BigDecimal close) {}

    public static EtfPriceSeriesResponse from(List<IndexPrice> prices) {
        List<Point> points = prices.stream()
                .map(p -> new Point(p.tradeDate(), p.close()))
                .toList();
        return new EtfPriceSeriesResponse(points);
    }
}
