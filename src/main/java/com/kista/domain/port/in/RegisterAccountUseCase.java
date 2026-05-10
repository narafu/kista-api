package com.kista.domain.port.in;

import com.kista.domain.model.Account;
import com.kista.domain.model.Strategy;

import java.util.UUID;

public interface RegisterAccountUseCase {
    Account register(UUID userId, Command command);

    record Command(
            String nickname,
            String accountNo,       // 평문 (서비스에서 암호화)
            String kisAppKey,       // 평문
            String kisSecretKey,    // 평문
            String kisAccountType,  // 기본값 "01"
            Strategy strategy,
            String telegramBotToken, // null 가능
            String telegramChatId,   // null 가능
            String symbol,           // 거래 종목 (기본값 "SOXL")
            String exchangeCode      // 해외거래소 코드 (기본값 "AMS")
    ) {}
}
