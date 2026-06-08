package com.kista.domain.port.in;

import com.kista.domain.model.kis.DailyTransactionResult;

import java.time.LocalDate;
import java.util.UUID;

// KIS CTOS4001R — 일별 거래내역 조회
public interface GetDailyTransactionsUseCase {
    DailyTransactionResult getDailyTransactions(UUID accountId, UUID requesterId, LocalDate from, LocalDate to);
}
