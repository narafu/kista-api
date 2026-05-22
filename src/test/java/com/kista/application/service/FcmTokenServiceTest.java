package com.kista.application.service;

import com.kista.domain.port.out.FcmDeviceTokenPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class FcmTokenServiceTest {

    @Mock FcmDeviceTokenPort fcmDeviceTokenPort;
    @InjectMocks FcmTokenService service;

    @Test
    void register_delegatesToPort() {
        UUID userId = UUID.randomUUID();
        service.register(userId, "token-abc", "WEB");
        verify(fcmDeviceTokenPort).save(userId, "token-abc", "WEB");
    }

    @Test
    void unregister_delegatesToPort() {
        UUID userId = UUID.randomUUID();
        service.unregister(userId, "token-abc");
        verify(fcmDeviceTokenPort).delete(userId, "token-abc");
    }
}
