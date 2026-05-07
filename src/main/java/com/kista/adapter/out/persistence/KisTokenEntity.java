package com.kista.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "kis_tokens")
class KisTokenEntity {

    @Id
    @Column(name = "account_id")
    private UUID accountId; // 계좌별 PK (멀티계좌 독립 토큰 관리)

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected KisTokenEntity() {}

    KisTokenEntity(UUID accountId, String accessToken, OffsetDateTime expiresAt) {
        this.accountId = accountId;
        this.accessToken = accessToken;
        this.expiresAt = expiresAt;
    }

    UUID getAccountId() { return accountId; }
    String getAccessToken() { return accessToken; }
    OffsetDateTime getExpiresAt() { return expiresAt; }
    Instant getCreatedAt() { return createdAt; }
}
