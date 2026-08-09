package com.kista.adapter.in.web.dto;

import com.kista.domain.model.asset.Asset;
import com.kista.domain.model.asset.AssetCategory;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.UUID;

public record AssetResponse(
        @Schema(description = "자산 기록 고유 ID")
        UUID id,
        @Schema(description = "기준 날짜", example = "2026-08-01")
        LocalDate entryDate,
        @Schema(description = "카테고리", example = "INVESTMENT")
        AssetCategory category,
        @Schema(description = "세부카테고리", example = "연금저축펀드")
        String subcategory,
        @Schema(description = "금융기관", example = "미래에셋증권")
        String institution,
        @Schema(description = "자산군", example = "미국주식")
        String assetClass,
        @Schema(description = "운용전략", example = "VR")
        String strategy,
        @Schema(description = "금액 (원화 정수)", example = "1000000")
        long amount
) {
    public static AssetResponse from(Asset a) {
        return new AssetResponse(
                a.id(), a.entryDate(), a.category(), a.subcategory(),
                a.institution(), a.assetClass(), a.strategy(), a.amount()
        );
    }
}
