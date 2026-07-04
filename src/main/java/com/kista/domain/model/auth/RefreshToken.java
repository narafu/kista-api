package com.kista.domain.model.auth;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(
        UUID id,           // 생성 시 null (@GeneratedValue)
        UUID userId,
        String tokenHash,  // SHA-256 hex(rawToken) — 64자
        String userAgent,  // nullable, 디바이스 식별용
        Instant expiresAt,
        Instant rotatedAt, // 회전 시각 — null이면 미회전, 60초 이내 재사용은 동시 경쟁 패자로 허용
        Instant createdAt  // 생성 시 null (DB DEFAULT now())
) {

    // 신규 발급 — id·rotatedAt·createdAt은 DB가 채움
    public static RefreshToken issue(UUID userId, String tokenHash, String userAgent, Instant expiresAt) {
        return new RefreshToken(null, userId, tokenHash, userAgent, expiresAt, null, null);
    }
}
