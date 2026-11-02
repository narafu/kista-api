package com.kista.tradingcore.trading.application.usecase;

import com.kista.tradingcore.trading.domain.model.WithdrawCapitalCommand;

import java.util.UUID;

public interface WithdrawCapitalUseCase {
    // 진행 중인 PRIVACY/INFINITE 사이클의 장부 예수금·startAmount 차감 — 사이클 유지, 장부만 갱신(브로커 호출 없음)
    void withdraw(UUID strategyId, UUID requesterId, WithdrawCapitalCommand cmd);
}
