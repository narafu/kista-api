package com.kista.domain.model.user;

import java.util.Map;
import java.util.UUID;

public record UserSettings(
        UUID userId,
        boolean balanceCheckEnabled,
        Map<NotificationType, Boolean> notificationPrefs
) {
    public boolean isNotificationEnabled(NotificationType type) {
        return notificationPrefs.getOrDefault(type, true);
    }

    public static UserSettings defaultFor(UUID userId) {
        return new UserSettings(userId, true, Map.of());
    }
}
