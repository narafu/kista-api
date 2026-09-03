package com.kista.strategyconfig.application.service;

import com.kista.account.application.port.output.AccountPort;
import com.kista.account.domain.model.Account;
import com.kista.strategyconfig.application.port.output.StrategyPort;
import com.kista.user.application.port.output.ActiveStrategyCountPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// ActiveStrategyCountPort 구현 — user↔strategy-config 순환 해소 산물.
// strategy-config는 account에 이미 정상 forward 의존이 있어(등록 시 계좌 조회) AccountPort 조합이 문제없다.
@Component
@RequiredArgsConstructor
class ActiveStrategyCountAdapter implements ActiveStrategyCountPort {

    private final AccountPort accountPort;
    private final StrategyPort strategyPort;

    @Override
    public long countActiveByUserId(UUID userId) {
        return accountPort.findByUserId(userId).stream()
                .map(Account::id)
                .flatMap(accountId -> strategyPort.findByAccountId(accountId).stream())
                .filter(strategy -> strategy.isActive())
                .count();
    }
}
