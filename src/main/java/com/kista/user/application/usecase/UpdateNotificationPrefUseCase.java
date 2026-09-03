package com.kista.user.application.usecase;

import com.kista.sharedkernel.NotificationType;
import java.util.UUID;

public interface UpdateNotificationPrefUseCase {
    void update(UpdateNotificationPrefCommand command);

    record UpdateNotificationPrefCommand(UUID userId, NotificationType type, boolean enabled) {}
}
