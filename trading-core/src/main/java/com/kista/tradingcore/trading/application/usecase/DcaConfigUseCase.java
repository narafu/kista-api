package com.kista.tradingcore.trading.application.usecase;

import com.kista.tradingcore.matching.domain.model.DcaConfig;

import java.util.UUID;

public interface DcaConfigUseCase {
    // DCA 설정 변경 — 새 strategy_version + DCA 상세 1행, 사이클 유지. ticker·direction 변경 불가
    void updateConfig(UUID strategyId, UUID requesterId, DcaConfig config);
}
