package com.kista.api.finance.adapter.in.web.dto;

import com.kista.api.finance.domain.model.AssetSnapshotAggregates;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

// 자산 구성비 응답 — 월 오름차순. item은 CATEGORY면 L1 카테고리 id, ASSET_CLASS면 자산군 이름
public record AssetCompositionResponse(List<AssetCompositionColumn> columns) {
    public static AssetCompositionResponse from(List<AssetSnapshotAggregates.CompositionColumn> columns) {
        return new AssetCompositionResponse(columns.stream().map(AssetCompositionColumn::from).toList());
    }

    public record AssetCompositionColumn(YearMonth month, long total, List<AssetCompositionEntry> entries) {
        static AssetCompositionColumn from(AssetSnapshotAggregates.CompositionColumn c) {
            return new AssetCompositionColumn(c.month(), c.total(),
                    c.entries().stream().map(e -> new AssetCompositionEntry(e.item(), e.amount(), e.percent())).toList());
        }
    }

    public record AssetCompositionEntry(String item, long amount, BigDecimal percent) {}
}
