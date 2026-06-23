package com.kista.adapter.out.persistence.kistoken;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "broker_tokens")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class KisTokenEntity extends BaseAuditEntity {

    @Id
    @Column(name = "account_id")
    private UUID accountId; // 계좌별 PK (멀티계좌 독립 토큰 관리)

    @Column(name = "access_token", nullable = false, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    KisTokenEntity(UUID accountId, String accessToken, OffsetDateTime expiresAt) {
        this.accountId = accountId;
        this.accessToken = accessToken;
        this.expiresAt = expiresAt;
    }
}
