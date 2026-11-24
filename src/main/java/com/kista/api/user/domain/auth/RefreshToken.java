package com.kista.api.user.domain.auth;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(
        UUID id,           // 생성 시 null (@UuidGenerator)
        UUID userId,
        String tokenHash,  // SHA-256 hex(rawToken) — 64자
        String userAgent,  // nullable, 디바이스 식별용
        Instant expiresAt,
        Instant rotatedAt, // 회전 시각 — null이면 미회전, 회전 행은 만료까지 보존(재시도·재사용 탐지용)
        Instant createdAt  // 생성 시 null (DB DEFAULT now())
) {

    // 신규 발급 — id·rotatedAt·createdAt은 DB가 채움
    public static RefreshToken issue(UUID userId, String tokenHash, String userAgent, Instant expiresAt) {
        return new RefreshToken(null, userId, tokenHash, userAgent, expiresAt, null, null);
    }
}
