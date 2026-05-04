package com.kista.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.OffsetDateTime;

@Entity
@Table(name = "kis_tokens")
class KisTokenEntity {

    @Id
    private int id; // 고정값 1 — 단일 행 upsert용

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken; // KIS OAuth 액세스 토큰

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt; // KST → OffsetDateTime(+09:00) 변환 후 저장

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt; // DB DEFAULT now() 자동 설정

    protected KisTokenEntity() {}

    KisTokenEntity(int id, String accessToken, OffsetDateTime expiresAt) {
        this.id = id;
        this.accessToken = accessToken;
        this.expiresAt = expiresAt;
    }

    int getId() { return id; }
    String getAccessToken() { return accessToken; }
    OffsetDateTime getExpiresAt() { return expiresAt; }
    Instant getCreatedAt() { return createdAt; }
}
