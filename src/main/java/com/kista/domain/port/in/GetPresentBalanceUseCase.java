package com.kista.domain.port.in;

import com.kista.domain.model.kis.PresentBalanceResult;

import java.util.UUID;

// KIS CTRP6504R — 체결 기준 현재 잔고 조회
public interface GetPresentBalanceUseCase {
    PresentBalanceResult getPresentBalance(UUID accountId, UUID requesterId);
}
