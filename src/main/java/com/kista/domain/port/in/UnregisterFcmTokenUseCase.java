package com.kista.domain.port.in;

import java.util.UUID;

public interface UnregisterFcmTokenUseCase {
    void unregister(UUID userId, String token);
}
