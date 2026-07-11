package com.kista.domain.port.in;

import java.time.Instant;
import java.util.UUID;

public interface BlacklistUseCase {
    boolean isBlacklisted(UUID userId);
    boolean isJtiBlacklisted(String jti); // 단일 AT jti 단위 블랙리스트 확인
    Instant roleChangedAt(UUID userId); // role 변경 시각 — 없으면 null (JwtAuthFilter stale AT 판정용)
}
