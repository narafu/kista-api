package com.kista.adapter.in.web.dto;

import com.kista.domain.model.asset.AssetCategory;
import com.kista.domain.model.asset.RegisterAssetCommand;
import com.kista.domain.model.asset.UpdateAssetCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

public record AssetRequest(
        @Schema(description = "기준 날짜", example = "2026-08-01")
        @NotNull LocalDate entryDate,
        @Schema(description = "카테고리", example = "INVESTMENT")
        @NotNull AssetCategory category,
        @Schema(description = "세부카테고리 (자유 입력)", example = "연금저축펀드")
        @NotBlank String subcategory,
        @Schema(description = "금융기관 (자유 입력, 선택)", example = "미래에셋증권")
        String institution,
        @Schema(description = "자산군 (자유 입력)", example = "미국주식")
        @NotBlank String assetClass,
        @Schema(description = "운용전략 (자유 입력, 선택 — 실제 자동매매 전략과 무관한 개인 메모)", example = "VR")
        String strategy,
        @Schema(description = "금액 (원화 정수, 0 이상)", example = "1000000")
        @PositiveOrZero long amount
) {
    public RegisterAssetCommand toRegisterCommand() {
        return new RegisterAssetCommand(entryDate, category, subcategory, institution, assetClass, strategy, amount);
    }

    public UpdateAssetCommand toUpdateCommand() {
        return new UpdateAssetCommand(entryDate, category, subcategory, institution, assetClass, strategy, amount);
    }
}
