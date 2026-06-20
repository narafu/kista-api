package com.kista.domain.port.out;

import com.kista.domain.model.user.UserSettings;

public interface SaveUserSettingsPort {
    void save(UserSettings settings);
}
