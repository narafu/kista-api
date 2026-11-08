package com.kista.tradingcore.trading.application.usecase;

import com.kista.tradingcore.trading.domain.model.AccountLedgerTotals;

import java.util.UUID;

public interface AccountLedgerTotalsUseCase {
    // 계좌 원장 합산 조회 — 소유권 검증 포함
    AccountLedgerTotals get(UUID accountId, UUID requesterId);
}
