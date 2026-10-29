package com.kista.tradingcore.stats.adapter.in.web.dto;

import com.kista.tradingcore.stats.domain.model.backtest.BacktestResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BacktestResponse(
        List<Point> points,
        Summary summary,
        @Schema(description = "두 번째 사이클부터 각 사이클이 시작된 날짜(points의 date와 같은 축, 오름차순). "
                + "첫 사이클 시작일(points[0].date)은 포함하지 않는다. 개수 = summary.cycleCount - 1 "
                + "(cycleCount는 진행 중인 마지막 사이클까지 센다). 사이클 전환이 없으면 빈 배열")
        List<LocalDate> cycleStartDates,
        List<String> warnings) {

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
        return new BacktestResponse(points, summary, result.cycleStartDates(), result.warnings());
    }
}
