package com.kista.tradingcore.account.adapter.out.persistence;

import com.kista.shared.platform.crypto.AesStringConverter;
import com.kista.shared.platform.persistence.BaseAuditEntity;
import com.kista.shared.kernel.Broker;
import com.kista.tradingcore.account.domain.model.Account;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts", schema = "trading")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AccountEntity extends BaseAuditEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Column(nullable = false, length = 100)
    private String nickname;              // 계좌 별칭

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Broker broker;       // 증권사

    @Convert(converter = AesStringConverter.class)
    @Column(name = "account_no", length = 512)
    private String accountNo;            // 평문 보유 — DB에는 AES-256 암호문 저장(컨버터), 업비트는 null

    @Column(name = "broker_account_code", length = 10)
    private String brokerAccountCode;    // 증권사 API 보조 식별자 — KIS: null, TOSS: accountSeq

    @Convert(converter = AesStringConverter.class)
    @Column(name = "app_key", nullable = false, length = 512)
    private String appKey;            // 평문 보유 — DB에는 AES-256 암호문 저장(컨버터)

    @Convert(converter = AesStringConverter.class)
    @Column(name = "secret_key", nullable = false, length = 512)
    private String secretKey;         // 평문 보유 — DB에는 AES-256 암호문 저장(컨버터)

    @Column(name = "account_no_hash", length = 64)
    private String accountNoHash;          // HMAC-SHA256 해시 (전역 중복 체크용, 기존 레코드는 NULL)

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨

    @Column(name = "api_key_expires_at")
    private Instant apiKeyExpiresAt; // 거래소 API 키 만료 시각 — 업비트만 값 (V10 ADD COLUMN이라 물리 순서상 마지막)

    // Account 도메인 모델 → Entity (accountNoHash는 HMAC 비밀키가 필요해 어댑터가 계산해 넘긴다)
    static AccountEntity fromModel(Account a, String accountNoHash) {
        AccountEntity e = new AccountEntity();
        e.id = a.id(); // null이면 @UuidGenerator가 UUID v7 생성
        e.userId = a.userId();
        e.nickname = a.nickname();
        e.accountNo = a.accountNo(); // 업비트는 계좌번호 없음(null)
        e.accountNoHash = accountNoHash;
        e.appKey = a.appKey();
        e.secretKey = a.secretKey();
        e.brokerAccountCode = a.brokerAccountCode();
        e.apiKeyExpiresAt = a.apiKeyExpiresAt();
        e.broker = a.broker();
        return e;
    }
}
