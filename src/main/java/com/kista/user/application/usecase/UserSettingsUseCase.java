package com.kista.user.application.usecase;

import com.kista.sharedkernel.NotificationType;

import java.util.List;
import java.util.UUID;

// 사용자 설정 변경 유스케이스 — 알림 타입, 잔고검증, 운영전략 추천 목록 (UserProfileUseCase와 대칭)
public interface UserSettingsUseCase {
    // --- 알림 타입 on/off ---
    void updateNotificationPref(UUID userId, NotificationType type, boolean enabled);

    // --- 잔고 검증 ---
    void updateBalanceCheck(UUID userId, boolean enabled);

    // --- 운영전략 추천 목록 ---
    void updateStrategySuggestions(UUID userId, List<String> suggestions);
}
