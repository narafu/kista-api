package com.kista.adapter.out.persistence.auth;

import com.kista.domain.model.auth.RefreshToken;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId; // 토큰 소유자

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash; // SHA-256 hex, 64자

    @Column(name = "user_agent", length = 512)
    private String userAgent; // nullable: 디바이스 식별용

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt; // 토큰 만료 시각

    @Column(name = "rotated_at")
    private Instant rotatedAt; // 회전 시각 — null이면 미회전

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt; // DB DEFAULT now()

    // 도메인 → 엔티티 변환 (id는 DB가 생성)
    static RefreshTokenEntity from(RefreshToken domain) {
        RefreshTokenEntity e = new RefreshTokenEntity();
        e.userId = domain.userId();
        e.tokenHash = domain.tokenHash();
        e.userAgent = domain.userAgent();
        e.expiresAt = domain.expiresAt();
        return e;
    }

    // 엔티티 → 도메인 변환
    RefreshToken toDomain() {
        return new RefreshToken(id, userId, tokenHash, userAgent, expiresAt, rotatedAt, createdAt);
    }
}
