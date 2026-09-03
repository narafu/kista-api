package com.kista.finance.adapter.in.web.dto;

import com.kista.finance.domain.model.AssetClass;
import com.kista.finance.domain.model.AssetSnapshotCommand;
import com.kista.finance.domain.model.FinanceTransactionCommand;
import com.kista.finance.domain.model.Market;
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
        @Valid @Size(max = 500) List<AssetItem> assets,
        @Valid @Size(max = 500) List<TransactionItem> transactions
) {
    public record AssetItem(
            @NotNull UUID categoryId,
            UUID accountId, // null 허용 — 계좌 없는 자산
            @NotNull LocalDate entryDate,
            @NotNull AssetClass assetClass,
            @NotNull Market market,
            String strategy,
            String memo,
            @PositiveOrZero long amount
    ) {
        public AssetSnapshotCommand toCommand() {
            return new AssetSnapshotCommand(categoryId, accountId, entryDate, assetClass, market, strategy, memo, amount);
        }
    }

    public record TransactionItem(
            @NotNull UUID categoryId,
            @NotNull LocalDate transactionDate,
            @PositiveOrZero long amount,
            @Size(max = 255) String memo
    ) {
        public FinanceTransactionCommand toCommand() {
            return new FinanceTransactionCommand(categoryId, transactionDate, amount, memo);
        }
    }
}
