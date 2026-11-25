package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.AssetSnapshotAggregates;
import org.jspecify.annotations.Nullable;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

// 자산 월 요약 응답 — month·current·previous는 기록이 없으면 null
public record AssetSummaryResponse(
        @Nullable YearMonth month,
        List<YearMonth> availableMonths,
        @Nullable MonthlySummary current,
        @Nullable MonthlySummary previous
) {
    public static AssetSummaryResponse from(AssetSnapshotAggregates.AssetSummaryResult r) {
        return new AssetSummaryResponse(r.month(), r.availableMonths(),
                r.current() == null ? null : MonthlySummary.from(r.current()),
                r.previous() == null ? null : MonthlySummary.from(r.previous()));
    }

    public record MonthlySummary(
            YearMonth month,
            long netWorth,
            long totalAssets,
            long totalLiabilities,
            @Nullable AssetClassAmount largestAssetClass,
            int recordCount,
            List<CategoryAmount> categoryBreakdown,
            List<AssetClassAmount> assetClassBreakdown
    ) {
        static MonthlySummary from(AssetSnapshotAggregates.MonthlyAssetSummary s) {
            return new MonthlySummary(s.month(), s.netWorth(), s.totalAssets(), s.totalLiabilities(),
                    s.largestAssetClass() == null ? null : AssetClassAmount.from(s.largestAssetClass()),
                    s.recordCount(),
                    s.categoryBreakdown().stream().map(CategoryAmount::from).toList(),
                    s.assetClassBreakdown().stream().map(AssetClassAmount::from).toList());
        }
    }

    public record CategoryAmount(UUID categoryId, long amount) {
        public static CategoryAmount from(AssetSnapshotAggregates.CategoryAmount c) {
            return new CategoryAmount(c.categoryId(), c.amount());
        }
    }

    public record AssetClassAmount(AssetClass assetClass, long amount) {
        public static AssetClassAmount from(AssetSnapshotAggregates.AssetClassAmount a) {
            return new AssetClassAmount(a.assetClass(), a.amount());
        }
    }
}
