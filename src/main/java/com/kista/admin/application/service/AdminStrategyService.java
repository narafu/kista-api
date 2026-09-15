package com.kista.admin.application.service;

import com.kista.admin.application.usecase.AdminStrategyUseCase;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.admin.application.port.output.TradingCommandPort;
import com.kista.sharedkernel.StrategyStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

// @Transactional 제거됨 — tradingCommandPort.updateStrategyStatus()가 HTTP 어댑터(내부 API 호출)라
// 순수 HTTP 위임이 됐고, "@Transactional 내부 외부 시스템 호출 금지" 규칙(constraints.md)과도 부합하지 않는다.
@Service
@RequiredArgsConstructor
class AdminStrategyService implements AdminStrategyUseCase {

    private final TradingCommandPort tradingCommandPort;
    private final AuditLogPort auditLogPort;

    @Override
    public void pauseStrategy(UUID adminId, UUID accountId, UUID strategyId) {
        tradingCommandPort.updateStrategyStatus(accountId, strategyId, StrategyStatus.PAUSED);
        auditLogPort.log(adminId, "STRATEGY_PAUSE", "STRATEGY", strategyId,
                Map.of("accountId", accountId.toString()));
    }

    @Override
    public void resumeStrategy(UUID adminId, UUID accountId, UUID strategyId) {
        tradingCommandPort.updateStrategyStatus(accountId, strategyId, StrategyStatus.ACTIVE);
        auditLogPort.log(adminId, "STRATEGY_RESUME", "STRATEGY", strategyId,
                Map.of("accountId", accountId.toString()));
    }
}
