package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.AssetSnapshotAggregates;

import java.time.YearMonth;
import java.util.List;

// 자산 추이 응답 — 월 오름차순, 모든 항목별 시계열을 한 번에 담는다
public record AssetTrendResponse(List<AssetTrendPoint> points) {
    public static AssetTrendResponse from(List<AssetSnapshotAggregates.TrendPoint> points) {
        return new AssetTrendResponse(points.stream().map(AssetTrendPoint::from).toList());
    }

    public record AssetTrendPoint(
            YearMonth month,
            long netWorth,
            long totalAssets,
            List<AssetSummaryResponse.CategoryAmount> byCategory,
            List<AssetSummaryResponse.AssetClassAmount> byAssetClass
    ) {
        static AssetTrendPoint from(AssetSnapshotAggregates.TrendPoint p) {
            return new AssetTrendPoint(p.month(), p.netWorth(), p.totalAssets(),
                    p.byCategory().stream().map(AssetSummaryResponse.CategoryAmount::from).toList(),
                    p.byAssetClass().stream().map(AssetSummaryResponse.AssetClassAmount::from).toList());
        }
    }
}
