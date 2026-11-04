package com.kista.api.admin.application.port.output;

import com.kista.shared.contract.trading.TradeCorrectionRequest;
import com.kista.shared.contract.trading.TradeCorrectionResponse;
import com.kista.shared.kernel.StrategyStatus;

import java.util.UUID;

public interface TradingCommandPort {
    TradeCorrectionResponse correctManualFills(TradeCorrectionRequest command);
    // 전략 일시중지/재개 — 내부에서 소유권 검증(strategy.accountId == accountId) 후 저장, 불일치 시 404
    void updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status);
}
