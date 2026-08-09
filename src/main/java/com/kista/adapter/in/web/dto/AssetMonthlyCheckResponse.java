package com.kista.adapter.in.web.dto;

import com.kista.domain.model.asset.AssetMonthlyCheck;
import io.swagger.v3.oas.annotations.media.Schema;

public record AssetMonthlyCheckResponse(
        @Schema(description = "연월", example = "2026-08")
        String month,
        @Schema(description = "기록 완료 여부", example = "true")
        boolean completed
) {
    public static AssetMonthlyCheckResponse from(AssetMonthlyCheck c) {
        return new AssetMonthlyCheckResponse(c.month(), c.completed());
    }
}
