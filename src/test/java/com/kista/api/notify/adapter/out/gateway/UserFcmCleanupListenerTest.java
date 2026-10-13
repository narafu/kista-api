package com.kista.api.notify.adapter.out.gateway;

import com.kista.shared.kernel.UserDeletedEvent;
import com.kista.api.notify.application.port.output.FcmDeviceTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class UserFcmCleanupListenerTest {

    @Mock FcmDeviceTokenPort fcmDeviceTokenPort;

    @Test
    void onUserDeleted_deletesAllFcmTokens() {
        UserFcmCleanupListener listener = new UserFcmCleanupListener(fcmDeviceTokenPort);
        UUID userId = UUID.randomUUID();

        listener.onUserDeleted(new UserDeletedEvent(userId));

        verify(fcmDeviceTokenPort).deleteAllByUserId(userId);
    }
}
