package com.kista.domain.port.in;

import java.util.UUID;

public interface UpdateUserTelegramUseCase {
    void updateTelegram(UUID userId, String botToken, String chatId);
    void removeTelegram(UUID userId);
}
