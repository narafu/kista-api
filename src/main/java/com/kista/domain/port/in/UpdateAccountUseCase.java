package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Ticker;

import java.util.UUID;

public interface UpdateAccountUseCase {
    Account update(UUID accountId, UUID requesterId, Command command);

    record Command(
            String nickname,
            String kisAppKey,           // null이면 기존값 유지
            String kisSecretKey,        // null이면 기존값 유지
            Ticker ticker,              // null이면 기존값 유지
            Account.StrategyType strategyType   // null이면 기존값 유지
    ) {}
}
