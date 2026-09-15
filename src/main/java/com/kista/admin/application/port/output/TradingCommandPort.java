package com.kista.admin.application.port.output;

import com.kista.admin.domain.model.AdminReorderCommand;
import com.kista.admin.domain.model.AdminReorderResult;
import com.kista.admin.domain.model.AdminReorderTimingAvailability;
import com.kista.admin.domain.model.AdminManualTradeCorrectionCommand;
import com.kista.admin.domain.model.AdminTradeCorrectionResult;
import com.kista.sharedkernel.StrategyStatus;

import java.util.UUID;

public interface TradingCommandPort {
    AdminReorderResult reorder(AdminReorderCommand command);
    AdminTradeCorrectionResult correctManualFills(AdminManualTradeCorrectionCommand command);
    AdminReorderTimingAvailability reorderTimingAvailability();
    // 전략 일시정지/재개 — 내부에서 소유권 검증(strategy.accountId == accountId) 후 저장, 불일치 시 404
    void updateStrategyStatus(UUID accountId, UUID strategyId, StrategyStatus status);
}
