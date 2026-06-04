package com.kista.domain.port.in;

import com.kista.domain.model.user.User.NotificationChannel;

import java.util.UUID;

public interface UpdateNotificationChannelUseCase {
    void updateNotificationChannel(UUID userId, NotificationChannel channel);
}
