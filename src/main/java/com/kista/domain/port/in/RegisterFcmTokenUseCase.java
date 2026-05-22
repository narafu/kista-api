package com.kista.domain.port.in;

import java.util.UUID;

public interface RegisterFcmTokenUseCase {
    void register(UUID userId, String token, String platform);
}
