package com.kista.broker.application.port.output;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

// KIS·Toss 공용 증권사 토큰 캐시 포트 — account_id PK 기준으로 증권사 독립적 관리
public interface BrokerTokenCachePort {
    // invalidateToken 호출 시 DB에 저장할 센티널 값 — findValidToken이 과거 만료 시각으로 인해 캐시 미스를 유도
    String INVALIDATED_TOKEN = "EXPIRED";

    Optional<String> findValidToken(UUID accountId, OffsetDateTime threshold);
    void saveToken(UUID accountId, String accessToken, OffsetDateTime expiresAt);
    void invalidateToken(UUID accountId, String rejectedAccessToken, OffsetDateTime invalidatedAt);
    // 탈퇴 정리 — 계좌들의 캐시 토큰 하드 삭제. 호출자 트랜잭션에 합류한다(REQUIRES_NEW 아님)
    void deleteByAccountIds(Collection<UUID> accountIds);
}
