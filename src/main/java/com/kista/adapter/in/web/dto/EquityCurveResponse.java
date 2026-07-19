package com.kista.adapter.in.web.dto;

import com.kista.domain.model.stats.EquityCurve;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EquityCurveResponse(List<Point> points) {

    @Schema(name = "EquityCurvePoint")
    public record Point(LocalDate date, BigDecimal totalAsset, BigDecimal principal) {}

    public static EquityCurveResponse from(EquityCurve curve) {
        return new EquityCurveResponse(
                curve.points().stream()
                        .map(p -> new Point(p.date(), p.totalAsset(), p.principal())).toList());
    }
}
