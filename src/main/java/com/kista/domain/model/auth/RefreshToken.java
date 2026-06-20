package com.kista.domain.model.auth;

import java.time.Instant;
import java.util.UUID;

public record RefreshToken(
        UUID id,           // 생성 시 null (@GeneratedValue)
        UUID userId,
        String tokenHash,  // SHA-256 hex(rawToken) — 64자
        String userAgent,  // nullable, 디바이스 식별용
        Instant expiresAt,
        Instant createdAt  // 생성 시 null (DB DEFAULT now())
) {}
