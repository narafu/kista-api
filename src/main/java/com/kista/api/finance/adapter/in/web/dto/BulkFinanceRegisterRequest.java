package com.kista.api.finance.adapter.in.web.dto;

import org.jspecify.annotations.Nullable;
import com.kista.api.finance.domain.model.AssetClass;
import com.kista.api.finance.domain.model.AssetSnapshotCommand;
import com.kista.api.finance.domain.model.FinanceTransactionCommand;
import com.kista.api.finance.domain.model.Market;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 자산/거래 기록을 한 번에 등록하는 요청 — 필드 shape는 AssetSnapshotRequest/FinanceTransactionRequest와 동일.
// 소스월→대상월 복제는 프론트(kista-ui)에서 각 항목의 entryDate/transactionDate를 대상월로 채워 보내는 방식으로
// 처리하고, 이 API 자체는 순수 flat 배치 등록만 담당한다.
public record BulkFinanceRegisterRequest(
        @Valid @Size(max = 500, message = "자산 기록은 500건 이내여야 합니다.") @Nullable List<AssetItem> assets,
        @Valid @Size(max = 500, message = "거래 기록은 500건 이내여야 합니다.") @Nullable List<TransactionItem> transactions
) {
    public record AssetItem(
            @NotNull(message = "카테고리는 필수입니다.") UUID categoryId,
            @Nullable UUID accountId, // null 허용 — 계좌 없는 자산
            @NotNull(message = "기준 날짜는 필수입니다.") LocalDate entryDate,
            @NotNull(message = "자산군은 필수입니다.") AssetClass assetClass,
            @NotNull(message = "시장은 필수입니다.") Market market,
            @Nullable String strategy,
            @Nullable String memo,
            @PositiveOrZero(message = "금액은 0 이상이어야 합니다.") long amount
    ) {
        public AssetSnapshotCommand toCommand() {
            return new AssetSnapshotCommand(categoryId, accountId, entryDate, assetClass, market, strategy, memo, amount);
        }
    }

    public record TransactionItem(
            @NotNull(message = "카테고리는 필수입니다.") UUID categoryId,
            @NotNull(message = "거래 날짜는 필수입니다.") LocalDate transactionDate,
            @PositiveOrZero(message = "금액은 0 이상이어야 합니다.") long amount,
            @Size(max = 255, message = "메모는 255자 이내여야 합니다.") @Nullable String memo
    ) {
        public FinanceTransactionCommand toCommand() {
            return new FinanceTransactionCommand(categoryId, transactionDate, amount, memo);
        }
    }
}
