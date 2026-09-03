package com.kista.user.application.usecase;

import com.kista.user.domain.model.UserSettings;
import java.util.UUID;

public interface GetUserSettingsQuery {
    UserSettings getByUserId(UUID userId);
}
