package com.kista.domain.port.out;

import com.kista.domain.model.user.UserSettings;
import java.util.Optional;
import java.util.UUID;

public interface UserSettingsPort {
    Optional<UserSettings> loadByUserId(UUID userId);
    void save(UserSettings settings);
}
