package com.kista.adapter.in.web.dto;

import com.kista.domain.model.stats.EquityCurve;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EquityCurveResponse(List<Point> points, List<BenchmarkPoint> benchmark) {

    public record Point(LocalDate date, BigDecimal totalAsset, BigDecimal principal) {}

    public record BenchmarkPoint(LocalDate date, BigDecimal close) {}

    public static EquityCurveResponse from(EquityCurve curve) {
        return new EquityCurveResponse(
                curve.points().stream()
                        .map(p -> new Point(p.date(), p.totalAsset(), p.principal())).toList(),
                curve.benchmark().stream()
                        .map(b -> new BenchmarkPoint(b.tradeDate(), b.close())).toList());
    }
}
