package com.kista.tradingcore.trading.application.service.support;

import com.kista.shared.kernel.Currency;
import com.kista.tradingcore.account.application.port.output.AccountPort;
import com.kista.tradingcore.account.domain.model.Account;
import com.kista.tradingcore.trading.application.port.output.StrategyPort;
import com.kista.tradingcore.trading.domain.model.Strategy;

import java.util.UUID;

// 소유권 검증을 마친 전략과 그 계좌 — 전략 조회 + 소유권 검증 패턴의 단일 구현(서비스마다 반복하지 않는다)
public record OwnedStrategy(Strategy strategy, Account account) {

    // 전략 조회 → 계좌 소유권 검증(불일치 시 SecurityException)
    public static OwnedStrategy load(StrategyPort strategyPort, AccountPort accountPort, UUID strategyId, UUID requesterId) {
        Strategy strategy = strategyPort.findByIdOrThrow(strategyId);
        return new OwnedStrategy(strategy, accountPort.requireOwnedAccount(strategy.accountId(), requesterId));
    }

    // 계좌 통화(원장 예수금 단위 — 주식 USD, 코인 견적 통화)
    public Currency currency() {
        return account.broker().getCurrency();
    }

    // 코인 거래소 계좌 여부
    public boolean crypto() {
        return account.broker().getMarket().isCrypto();
    }
}
