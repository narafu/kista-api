package com.kista.user.application.usecase;

import com.kista.sharedkernel.NotificationChannel;

import java.util.UUID;

// 사용자 프로필/설정 유스케이스 — 텔레그램 연동, 알림 채널, 닉네임
public interface UserProfileUseCase {
    // --- 텔레그램 ---
    void updateTelegram(UUID userId, String botToken, String chatId);
    void removeTelegram(UUID userId);

    // --- 알림채널 ---
    void updateNotificationChannel(UUID userId, NotificationChannel channel);

    // --- 닉네임 ---
    void updateNickname(UUID userId, String nickname);
}
