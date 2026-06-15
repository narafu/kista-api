package com.kista.adapter.out.persistence.account;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.account.Account;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AccountEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Column(nullable = false, length = 100)
    private String nickname;              // 계좌 별칭

    @Column(name = "account_no", nullable = false, length = 512)
    private String accountNo;            // AES-256 암호화 저장

    @Column(name = "app_key", nullable = false, length = 512)
    private String appKey;            // AES-256 암호화 저장

    @Column(name = "secret_key", nullable = false, length = 512)
    private String secretKey;         // AES-256 암호화 저장

    @Column(name = "kis_account_type", nullable = false, length = 10)
    private String kisAccountType;       // 계좌 상품 코드 (기본: 01)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Account.Broker broker;       // 증권사

    @Column(name = "account_no_hash", length = 64)
    private String accountNoHash;          // HMAC-SHA256 해시 (전역 중복 체크용, 기존 레코드는 NULL)

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

}
