package com.kista.adapter.in.web.dto;

import com.kista.domain.model.stats.EquityCurve;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EquityCurveResponse(List<Point> points) {

    public record Point(LocalDate date, BigDecimal totalAsset, BigDecimal principal) {}

    public static EquityCurveResponse from(EquityCurve curve) {
        return new EquityCurveResponse(
                curve.points().stream()
                        .map(p -> new Point(p.date(), p.totalAsset(), p.principal())).toList());
    }
}
