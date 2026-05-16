package com.kista.domain.port.in;

import com.kista.domain.model.Account;
import com.kista.domain.model.StrategyType;
import com.kista.domain.model.Ticker;

import java.util.UUID;

public interface RegisterAccountUseCase {
    Account register(UUID userId, Command command);

    record Command(
            String nickname,
            String accountNo,       // 평문 (서비스에서 암호화)
            String kisAppKey,       // 평문
            String kisSecretKey,    // 평문
            String kisAccountType,  // 기본값 "01"
            StrategyType strategyType,
            String telegramBotToken, // null 가능
            String telegramChatId,   // null 가능
            Ticker ticker            // null이면 전략에 따라 결정 (PRIVACY=SOXL, INFINITE=TQQQ)
    ) {}
}
