package com.kista.domain.model.user;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record UserSettings(
        UUID userId,
        boolean balanceCheckEnabled,
        Map<NotificationType, Boolean> notificationPrefs,
        List<String> strategySuggestions // 자산 등록 폼 운용전략 추천 목록 (자유입력을 돕는 값일 뿐 값 제한 아님)
) {
    // 운영전략 추천 목록 신규 유저 기본값 — 과거 admin 전역 설정(AssetFormOptions) 기본값과 동일
    public static final List<String> DEFAULT_STRATEGY_SUGGESTIONS = List.of("VR", "INFINITE", "PRIVACY", "DCA");

    public UserSettings {
        strategySuggestions = List.copyOf(Objects.requireNonNull(strategySuggestions, "strategySuggestions"));
    }

    public boolean isNotificationEnabled(NotificationType type) {
        return notificationPrefs.getOrDefault(type, true);
    }

    public UserSettings withBalanceCheckEnabled(boolean enabled) {
        return new UserSettings(userId, enabled, notificationPrefs, strategySuggestions);
    }

    public UserSettings withNotificationPrefs(Map<NotificationType, Boolean> prefs) {
        return new UserSettings(userId, balanceCheckEnabled, prefs, strategySuggestions);
    }

    public UserSettings withStrategySuggestions(List<String> suggestions) {
        return new UserSettings(userId, balanceCheckEnabled, notificationPrefs, suggestions);
    }

    public static UserSettings defaultFor(UUID userId) {
        return new UserSettings(userId, true, Map.of(), DEFAULT_STRATEGY_SUGGESTIONS);
    }
}
