package com.kista.adapter.out.persistence;

import com.kista.domain.model.Strategy;
import com.kista.domain.model.StrategyStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "UUID")
    private UUID userId;                  // FK → users.id

    @Column(nullable = false, length = 100)
    private String nickname;              // 계좌 별칭

    @Column(name = "account_no", nullable = false, length = 255)
    private String accountNo;            // AES-256 암호화 저장

    @Column(name = "kis_app_key", nullable = false, length = 255)
    private String kisAppKey;            // AES-256 암호화 저장

    @Column(name = "kis_secret_key", nullable = false, length = 255)
    private String kisSecretKey;         // AES-256 암호화 저장

    @Column(name = "kis_account_type", nullable = false, length = 10)
    private String kisAccountType;       // 계좌 상품 코드 (기본: 01)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Strategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_status", nullable = false, length = 10)
    private StrategyStatus strategyStatus;

    @Column(name = "telegram_bot_token", length = 255)
    private String telegramBotToken;     // 계좌별 봇 (optional, AES-256)

    @Column(name = "telegram_chat_id", length = 50)
    private String telegramChatId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
