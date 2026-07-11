package com.kista.domain.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public interface BlacklistPort {
    void add(UUID userId, Duration ttl); // AT TTL과 동일 기간 차단
    boolean isBlacklisted(UUID userId);

    void addJti(String jti, Duration ttl); // 단일 AT의 jti를 TTL 동안 블랙리스트에 등록
    boolean isJtiBlacklisted(String jti); // 해당 jti가 블랙리스트에 있으면 true

    void markRoleChanged(UUID userId, Instant changedAt, Duration ttl); // role 변경 시각 기록 — 이전 발급 AT 무효화용
    Instant roleChangedAt(UUID userId); // 기록 없으면 null
}
