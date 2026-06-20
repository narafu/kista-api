package com.kista.domain.port.out;

import com.kista.domain.model.user.UserSettings;
import java.util.Optional;
import java.util.UUID;

public interface LoadUserSettingsPort {
    Optional<UserSettings> loadByUserId(UUID userId);
}
