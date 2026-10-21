package com.kista.tradingcore.trading.application.usecase;

import com.kista.tradingcore.matching.domain.model.DcaStrategyConfig;

import java.util.UUID;

public interface DcaConfigUseCase {
    // DCA 규칙 설정 변경 — 새 strategy_version + 규칙 행, 사이클 유지. ruleKey null=신규 규칙, 기존 키는 보존(없는 키는 400)
    void updateConfig(UUID strategyId, UUID requesterId, DcaStrategyConfig requested);
}
