package com.kista.adapter.out.persistence.account;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.strategy.Ticker;
import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.*;

import java.math.BigDecimal;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "strategies")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "UUID")
    private UUID accountId; // FK → accounts.id (ON DELETE CASCADE)

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private Account.StrategyType type; // 매매 전략 종류 (INFINITE, PRIVACY)

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private Ticker ticker; // 거래 종목 코드

    @Column(name = "multiple", nullable = false, precision = 4, scale = 1)
    private BigDecimal multiple; // 배수 (기본값 1.0)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Account.StrategyStatus status; // 실행 상태 (ACTIVE, PAUSED)
}
