package com.kista.adapter.in.web.dto;

import com.kista.domain.model.finance.AssetClass;
import com.kista.domain.model.finance.AssetSnapshotCommand;
import com.kista.domain.model.finance.FinanceTransactionCommand;
import com.kista.domain.model.finance.Market;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 소스월 자산/거래 기록을 대상월로 일괄 등록하는 요청 — 필드 shape는 AssetSnapshotRequest/FinanceTransactionRequest와 동일
public record BulkFinanceRegisterRequest(
        @Valid List<AssetItem> assets,
        @Valid List<TransactionItem> transactions
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
