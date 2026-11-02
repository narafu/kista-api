package com.kista.tradingcore.trading.application.usecase;

import com.kista.tradingcore.trading.domain.model.InjectCapitalCommand;

import java.util.UUID;

public interface InjectCapitalUseCase {
    // 진행 중인 PRIVACY/INFINITE 사이클에 예수금·주식 추가 — 사이클 유지, 포지션 스냅샷 append, startAmount 가산
    void inject(UUID strategyId, UUID requesterId, InjectCapitalCommand cmd);
}
