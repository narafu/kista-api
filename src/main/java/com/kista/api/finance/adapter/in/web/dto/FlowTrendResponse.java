package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.FlowTrendPoint;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

public record FlowTrendResponse(
        @Schema(description = "오래된 순 추이 점")
        List<Point> points
) {
    @Schema(name = "FlowTrendPoint")
    public record Point(
            @Schema(description = "월간 yyyy-MM, 연간 yyyy", example = "2026-08")
            String period,
            @Schema(description = "버킷 합계 (원화 정수)", example = "1500000")
            long amount,
            @Schema(description = "루트(L1) 카테고리별 합계 — 금액 있는 루트만, 정렬순서 순")
            List<RootAmount> byCategory
    ) {}

    @Schema(name = "FlowTrendRootAmount")
    public record RootAmount(
            @Schema(description = "루트 카테고리 ID")
            UUID categoryId,
            @Schema(description = "합계 (원화 정수)")
            long amount
    ) {}

    public static FlowTrendResponse from(List<FlowTrendPoint> points) {
        return new FlowTrendResponse(points.stream()
                .map(p -> new Point(p.period(), p.amount(),
                        p.byCategory().stream().map(r -> new RootAmount(r.categoryId(), r.amount())).toList()))
                .toList());
    }
}
