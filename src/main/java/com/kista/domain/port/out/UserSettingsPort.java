package com.kista.domain.port.out;

import com.kista.domain.model.user.UserSettings;
import java.util.Optional;
import java.util.UUID;

public interface UserSettingsPort {
    Optional<UserSettings> loadByUserId(UUID userId);
    void save(UserSettings settings);

    // 설정이 없으면 기본값 반환 — 조회+fallback 반복 패턴 SSOT
    default UserSettings findOrDefault(UUID userId) {
        return loadByUserId(userId).orElseGet(() -> UserSettings.defaultFor(userId));
    }
}
