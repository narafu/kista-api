package com.kista.domain.port.in;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Ticker;

import java.math.BigDecimal;
import java.util.UUID;

public interface RegisterAccountUseCase {
    Account register(UUID userId, Command command);

    record Command(
            String nickname,
            String accountNo,       // 평문 (서비스에서 암호화)
            String kisAppKey,       // 평문
            String kisSecretKey,    // 평문
            String kisAccountType,  // 기본값 "01"
            Account.StrategyType strategyType,
            Ticker ticker,           // null이면 전략에 따라 결정 (PRIVACY=SOXL, INFINITE=TQQQ)
            BigDecimal multiple      // null이면 기본값 1.0
    ) {}
}
