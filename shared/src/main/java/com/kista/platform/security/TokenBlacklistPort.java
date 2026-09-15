package com.kista.platform.security;

import java.time.Instant;
import java.util.UUID;

// JwtAuthFilter가 필요로 하는 읽기 전용 블랙리스트 조회 — 쓰기(add/addJti/markRoleChanged)는
// root의 BlacklistPort(로그아웃·강퇴 흐름 전용)가 그대로 담당, 이 포트는 필터의 읽기 3종만 좁힌 것.
public interface TokenBlacklistPort {
    boolean isBlacklisted(UUID userId);
    boolean isJtiBlacklisted(String jti);
    Instant roleChangedAt(UUID userId);
}
