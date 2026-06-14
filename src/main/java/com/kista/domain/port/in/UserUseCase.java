package com.kista.domain.port.in;

import com.kista.domain.model.user.User;
import com.kista.domain.model.user.User.NotificationChannel;

import java.util.UUID;

public interface UserUseCase {
    // --- 조회 ---
    User getById(UUID id);
    User getByKakaoId(String kakaoId);

    // --- 카카오 로그인 ---
    User login(String code, String redirectUri);

    // --- 회원가입 ---
    User register(String kakaoId, String nickname, UUID userId);

    // --- 승인 ---
    void approve(UUID userId);
    void reject(UUID userId);
    void reapply(UUID userId);

    // --- 탈퇴 ---
    void deleteMe(UUID userId);

    // --- 텔레그램 ---
    void updateTelegram(UUID userId, String botToken, String chatId);
    void removeTelegram(UUID userId);

    // --- 알림채널 ---
    void updateNotificationChannel(UUID userId, NotificationChannel channel);

    // --- 잔고 검증 설정 ---
    void updateBalanceCheckEnabled(UUID userId, boolean enabled);

    // --- FCM ---
    void registerFcmToken(UUID userId, String token, String platform);
    void unregisterFcmToken(UUID userId, String token);
}
