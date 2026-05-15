package com.kista.adapter.in.web.dto;

import com.kista.domain.model.User;

// chatId만 반환 — botToken은 보안상 미노출
public record TelegramSettingsResponse(
        boolean configured, // 텔레그램 설정 여부
        String chatId       // 텔레그램 Chat ID (평문, 민감정보 아님)
) {
    public static TelegramSettingsResponse from(User user) {
        return new TelegramSettingsResponse(
                user.telegramChatId() != null,
                user.telegramChatId()
        );
    }
}
