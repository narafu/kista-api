package com.kista.api.user.application.service;

import com.kista.shared.kernel.NotificationType;
import com.kista.api.user.domain.model.UserSettings;
import com.kista.api.user.application.port.output.UserSettingsPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 사용자 설정 변경 서비스 — 알림 타입, 운영전략 추천 목록 (UserProfileService와 대칭)
@Service
@RequiredArgsConstructor
@Slf4j
public class UserSettingsService {

    private final UserSettingsPort userSettingsPort;
    private final UserNotifyProfilePublisher userNotifyProfilePublisher; // trading-core 캐시 동기화 이벤트 발행

    public UserSettings getByUserId(UUID userId) {
        // 저장된 설정이 없으면 기본값 반환 (빈 알림 prefs)
        return userSettingsPort.findOrDefault(userId);
    }

    // 알림 타입 on/off
    @Transactional
    public void updateNotificationPref(UUID userId, NotificationType type, boolean enabled) {
        UserSettings current = getByUserId(userId);
        // 기존 prefs에 변경 항목만 덮어씀
        Map<NotificationType, Boolean> updatedPrefs = new HashMap<>(current.notificationPrefs());
        updatedPrefs.put(type, enabled);
        UserSettings updated = current.withNotificationPrefs(updatedPrefs);
        userSettingsPort.save(updated);
        log.info("[userId={}] 알림 설정 변경: type={}, enabled={}", userId, type, enabled);
        userNotifyProfilePublisher.publishSettingsChanged(updated);
    }

    // trading-core 복제본 재동기화: 현재 상태·설정을 변경 없이 재발행
    @Transactional
    public void resyncNotifyProfile(UUID userId) {
        // 저장 없이 이벤트만 — 유실된 trading.user_notify_profile 복제본을 현재 값으로 다시 채운다(upsert라 멱등)
        userNotifyProfilePublisher.publishSettingsChanged(getByUserId(userId));
    }

    // 운영전략 추천 목록
    @Transactional
    public void updateStrategySuggestions(UUID userId, List<String> suggestions) {
        UserSettings current = getByUserId(userId);
        userSettingsPort.save(current.withStrategySuggestions(suggestions));
        log.info("[userId={}] 운영전략 추천 목록 변경: count={}", userId, suggestions.size());
    }
}
