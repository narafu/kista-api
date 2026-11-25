package com.kista.api.finance.domain.model;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

// 자산 스냅샷 집계 결과 값 모음 — UI aggregate.ts(calc*)와 같은 값을 서버가 계산해 돌려준다.
public final class AssetSnapshotAggregates {

    private AssetSnapshotAggregates() {}

    // 구성비 기준: L1 카테고리(대출 포함) 또는 자산군(대출 제외)
    public enum Basis { CATEGORY, ASSET_CLASS }

    public record CategoryAmount(UUID categoryId, long amount) {}

    public record AssetClassAmount(AssetClass assetClass, long amount) {}

    public record MonthlyAssetSummary(
            YearMonth month,
            long netWorth,                                  // 총자산 - 총부채
            long totalAssets,                               // 대출 제외 합
            long totalLiabilities,                          // 대출 합
            @Nullable AssetClassAmount largestAssetClass,   // 대출 제외 최대 자산군, 없으면 null
            int recordCount,
            List<CategoryAmount> categoryBreakdown,         // L1 4개 고정 순서(0 포함)
            List<AssetClassAmount> assetClassBreakdown      // 부채 제외, enum 순서, 0원 제외
    ) {}

    public record AssetSummaryResult(
            @Nullable YearMonth month,
            List<YearMonth> availableMonths,                // 내림차순
            @Nullable MonthlyAssetSummary current,
            @Nullable MonthlyAssetSummary previous
    ) {}

    public record TrendPoint(
            YearMonth month,
            long netWorth,
            long totalAssets,
            List<CategoryAmount> byCategory,                // L1 4개 고정 순서(대출 포함)
            List<AssetClassAmount> byAssetClass             // 6개 전부(부채 제외)
    ) {}

    public record CompositionEntry(String item, long amount, BigDecimal percent) {}

    public record CompositionColumn(YearMonth month, long total, List<CompositionEntry> entries) {}

    public record MissingAccount(@Nullable String accountName, String categoryName, AssetClass assetClass) {}

    public record DateGroup(LocalDate date, long count) {}

    public record AssetCheckResult(
            YearMonth month,
            @Nullable YearMonth previousMonth,
            List<UUID> missingCategoryIds,
            List<MissingAccount> missingAccounts,
            List<DateGroup> dateGroups
    ) {}
}
