package com.kista.adapter.in.web.dto;

import com.kista.domain.model.User;
import com.kista.domain.model.UserStatus;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String nickname,
        UserStatus status,
        boolean hasTelegram
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.id(),
                user.nickname(),
                user.status(),
                user.telegramChatId() != null
        );
    }
}
