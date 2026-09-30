package com.kista.admin.application.port.output;

import com.kista.contract.trading.ReorderRequest;
import com.kista.contract.trading.ReorderResponse;
import com.kista.contract.trading.ReorderTimingAvailabilityResponse;
import com.kista.contract.trading.TradeCorrectionRequest;
import com.kista.contract.trading.TradeCorrectionResponse;
import com.kista.sharedkernel.StrategyStatus;

import java.util.UUID;

public interface TradingCommandPort {
    ReorderResponse reorder(ReorderRequest command);
    TradeCorrectionResponse correctManualFills(TradeCorrectionRequest command);
    ReorderTimingAvailabilityResponse reorderTimingAvailability();
    // 전략 일시정지/재개 — 내부에서 소유권 검증(strategy.accountId == accountId) 후 저장, 불일치 시 404
    void updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status);
}
