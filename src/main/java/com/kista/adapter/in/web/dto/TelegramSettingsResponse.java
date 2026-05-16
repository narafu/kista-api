package com.kista.adapter.in.web.dto;

import com.kista.domain.model.User;
import io.swagger.v3.oas.annotations.media.Schema;

// chatId만 반환 — botToken은 보안상 미노출
public record TelegramSettingsResponse(
        @Schema(description = "텔레그램 설정 여부", example = "true")
        boolean configured,
        @Schema(description = "텔레그램 채팅 ID (botToken은 보안상 미노출)", example = "-1001234567890")
        String chatId
) {
    public static TelegramSettingsResponse from(User user) {
        return new TelegramSettingsResponse(
                user.telegramChatId() != null,
                user.telegramChatId()
        );
    }
}
