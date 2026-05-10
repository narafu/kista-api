package com.kista.domain.port.in;

import com.kista.domain.model.Account;

import java.util.UUID;

public interface UpdateAccountUseCase {
    Account update(UUID accountId, UUID requesterId, Command command);

    record Command(
            String nickname,
            String kisAppKey,        // null이면 기존값 유지
            String kisSecretKey,     // null이면 기존값 유지
            String telegramBotToken, // null 가능
            String telegramChatId,   // null 가능
            String symbol,           // null이면 기존값 유지
            String exchangeCode      // null이면 기존값 유지
    ) {}
}
