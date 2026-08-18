package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.AssetClass;
import com.kista.domain.model.finance.AssetSnapshotCommand;
import com.kista.domain.model.finance.Market;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;
import java.util.UUID;

// 등록·수정 공용
public record AssetSnapshotRequest(
        @Schema(description = "카테고리 ID (type=ASSET)")
        @NotNull UUID categoryId,
        @Schema(description = "계좌 ID (선택 — 전세임차보증금 등 계좌 없는 자산은 null)")
        UUID accountId,
        @Schema(description = "기준 날짜", example = "2026-08-01")
        @NotNull LocalDate entryDate,
        @Schema(description = "자산군", example = "EQUITY")
        @NotNull AssetClass assetClass,
        @Schema(description = "시장", example = "GLOBAL")
        @NotNull Market market,
        @Schema(description = "운용전략 (자유 입력, 선택 — 실제 자동매매 전략과 무관한 개인 메모)", example = "VR")
        String strategy,
        @Schema(description = "금액 (원화 정수, 0 이상)", example = "1000000")
        @PositiveOrZero long amount
) {
    public AssetSnapshotCommand toCommand() {
        return new AssetSnapshotCommand(categoryId, accountId, entryDate, assetClass, market, strategy, amount);
    }
}
