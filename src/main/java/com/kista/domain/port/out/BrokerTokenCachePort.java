package com.kista.domain.port.out;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

// KIS·Toss 공용 증권사 토큰 캐시 포트 — account_id PK 기준으로 증권사 독립적 관리
public interface BrokerTokenCachePort {
    Optional<String> findValidToken(UUID accountId, OffsetDateTime threshold);
    void saveToken(UUID accountId, String accessToken, OffsetDateTime expiresAt);
}
