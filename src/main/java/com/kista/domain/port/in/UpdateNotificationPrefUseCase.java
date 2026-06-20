package com.kista.domain.port.in;

import com.kista.domain.model.user.NotificationType;
import java.util.UUID;

public interface UpdateNotificationPrefUseCase {
    void update(UpdateNotificationPrefCommand command);

    record UpdateNotificationPrefCommand(UUID userId, NotificationType type, boolean enabled) {}
}
