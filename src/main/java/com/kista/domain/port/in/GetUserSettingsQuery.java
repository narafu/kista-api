package com.kista.domain.port.in;

import com.kista.domain.model.user.UserSettings;
import java.util.UUID;

public interface GetUserSettingsQuery {
    UserSettings getByUserId(UUID userId);
}
