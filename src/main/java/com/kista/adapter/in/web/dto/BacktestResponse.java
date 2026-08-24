package com.kista.adapter.in.web.dto;

import com.kista.domain.model.backtest.BacktestResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestResponse(List<Point> points, Summary summary, List<String> warnings) {

    // 필드명은 EquityCurveResponse.Point와 동일 — 프론트 자산 곡선 차트 재사용
    @Schema(name = "BacktestPoint")
    public record Point(LocalDate date, BigDecimal totalAsset, BigDecimal principal) {}

    @Schema(name = "BacktestSummary")
    public record Summary(BigDecimal finalAsset, BigDecimal totalInvested, BigDecimal totalReturnRate,
                          BigDecimal cagr, BigDecimal mdd, int tradeCount, int cycleCount) {}

    public static BacktestResponse from(BacktestResult result) {
        List<Point> points = result.points().stream()
                .map(p -> new Point(p.date(), p.totalAsset(), p.principal()))
                .toList();
        Summary summary = new Summary(result.summary().finalAsset(), result.summary().totalInvested(),
                result.summary().totalReturnRate(), result.summary().cagr(), result.summary().mdd(),
                result.summary().tradeCount(), result.summary().cycleCount());
        return new BacktestResponse(points, summary, result.warnings());
    }
}
