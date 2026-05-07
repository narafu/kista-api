package com.kista.domain.port.out;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface KisTokenCachePort {
    Optional<String> findValidToken(UUID accountId, OffsetDateTime now);
    void saveToken(UUID accountId, String accessToken, OffsetDateTime expiresAt);
}
