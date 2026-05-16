package com.kista.domain.port.in;

import com.kista.domain.model.Account;
import com.kista.domain.model.Ticker;

import java.util.UUID;

public interface UpdateAccountUseCase {
    Account update(UUID accountId, UUID requesterId, Command command);

    record Command(
            String nickname,
            String kisAppKey,        // null이면 기존값 유지
            String kisSecretKey,     // null이면 기존값 유지
            String telegramBotToken, // null 가능
            String telegramChatId,   // null 가능
            Ticker ticker            // null이면 기존값 유지
    ) {}
}
