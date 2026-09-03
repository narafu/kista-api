package com.kista.admin.application.service;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Strategy;
import com.kista.admin.application.usecase.AdminStrategyUseCase;
import com.kista.application.port.output.AccountPort;
import com.kista.admin.application.port.output.AuditLogPort;
import com.kista.application.port.output.StrategyPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
class AdminStrategyService implements AdminStrategyUseCase {

    private final StrategyPort strategyPort;
    private final AccountPort accountPort;
    private final AuditLogPort auditLogPort;

    @Override
    public void pauseStrategy(UUID adminId, UUID accountId, UUID strategyId) {
        Strategy strategy = requireOwnedByAccount(accountId, strategyId);
        strategyPort.save(strategy.withStatus(Strategy.Status.PAUSED));
        auditLogPort.log(adminId, "STRATEGY_PAUSE", "STRATEGY", strategyId,
                Map.of("accountId", accountId.toString()));
    }

    @Override
    public void resumeStrategy(UUID adminId, UUID accountId, UUID strategyId) {
        Strategy strategy = requireOwnedByAccount(accountId, strategyId);
        strategyPort.save(strategy.withStatus(Strategy.Status.ACTIVE));
        auditLogPort.log(adminId, "STRATEGY_RESUME", "STRATEGY", strategyId,
                Map.of("accountId", accountId.toString()));
    }

    private Strategy requireOwnedByAccount(UUID accountId, UUID strategyId) {
        Account account = accountPort.findByIdOrThrow(accountId);
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        if (!strategy.accountId().equals(account.id())) {
            throw new IllegalArgumentException("strategy가 account에 속하지 않습니다");
        }
        return strategy;
    }
}
