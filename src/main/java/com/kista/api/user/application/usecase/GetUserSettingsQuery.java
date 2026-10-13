package com.kista.api.user.application.usecase;

import com.kista.api.user.domain.model.UserSettings;
import java.util.UUID;

public interface GetUserSettingsQuery {
    UserSettings getByUserId(UUID userId);
}
