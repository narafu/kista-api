package com.kista.application.port.output;

import com.kista.domain.model.user.UserSettings;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface UserSettingsPort {
    Optional<UserSettings> loadByUserId(UUID userId);
    void save(UserSettings settings);

    // 설정이 없으면 기본값 반환 — 조회+fallback 반복 패턴 SSOT
    default UserSettings findOrDefault(UUID userId) {
        return loadByUserId(userId).orElseGet(() -> UserSettings.defaultFor(userId));
    }

    // 여러 사용자의 설정을 배치 조회 (N+1 방지) — 없는 사용자는 기본값으로 채움
    Map<UUID, UserSettings> findOrDefaultByUserIds(Collection<UUID> userIds);
}
