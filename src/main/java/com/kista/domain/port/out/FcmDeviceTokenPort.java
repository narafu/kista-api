package com.kista.domain.port.out;

import java.util.List;
import java.util.UUID;

public interface FcmDeviceTokenPort {
    void save(UUID userId, String token, String platform);
    void delete(UUID userId, String token);
    List<String> findTokensByUserId(UUID userId);
}
