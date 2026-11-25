package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.AssetSnapshotAggregates;
import org.jspecify.annotations.Nullable;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

// 월별 기록 점검 응답 — 라벨 조립(계좌 미지정·자산군 라벨)은 UI 몫
public record AssetCheckResponse(
        YearMonth month,
        @Nullable YearMonth previousMonth,
        List<UUID> missingCategoryIds,
        List<MissingAccount> missingAccounts,
        List<DateGroup> dateGroups
) {
    public static AssetCheckResponse from(AssetSnapshotAggregates.AssetCheckResult r) {
        return new AssetCheckResponse(r.month(), r.previousMonth(), r.missingCategoryIds(),
                r.missingAccounts().stream().map(a -> new MissingAccount(a.accountName(), a.categoryName(), a.assetClass())).toList(),
                r.dateGroups().stream().map(d -> new DateGroup(d.date(), d.count())).toList());
    }

    public record MissingAccount(@Nullable String accountName, String categoryName, AssetClass assetClass) {}

    public record DateGroup(LocalDate date, long count) {}
}
