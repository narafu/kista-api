package com.kista.domain.port.out;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KisTokenCachePort {
    Optional<String> findValidToken(UUID accountId, OffsetDateTime now);
    void saveToken(UUID accountId, String accessToken, OffsetDateTime expiresAt);
    // 선제 갱신 스케줄러용 — threshold 이전에 만료될 계좌 ID 목록 반환
    List<UUID> findExpiringAccountIds(OffsetDateTime threshold);
}
