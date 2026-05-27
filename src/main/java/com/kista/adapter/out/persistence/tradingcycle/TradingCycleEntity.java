package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trading_cycle")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TradingCycleEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "UUID")
    private UUID accountId; // FK → accounts.id (ON DELETE CASCADE)

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TradingCycle.Type type; // 매매 전략 종류 (INFINITE, PRIVACY)

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private TradingCycle.Ticker ticker; // 거래 종목 코드

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TradingCycle.Status status; // 실행 상태 (ACTIVE, PAUSED)

    @Column(name = "initial_usd_deposit", precision = 20, scale = 2)
    private BigDecimal initialUsdDeposit; // 사이클 시작 시 초기 입금액 (메타 기록용, nullable)

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}
