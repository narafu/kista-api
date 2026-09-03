package com.kista.adapter.out.persistence.strategy;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.strategy.Strategy;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;
import com.kista.sharedkernel.StrategyType;
import com.kista.sharedkernel.StrategyStatus;
import com.kista.sharedkernel.StrategyTicker;
import com.kista.sharedkernel.StrategyCycleSeedType;

@Entity
@Table(name = "strategy", schema = "kista")
@SQLRestriction("deleted_at IS NULL")
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
    private StrategyType type; // 매매 전략 종류 (INFINITE, PRIVACY)

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private StrategyTicker ticker; // 거래 종목 코드

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StrategyStatus status; // 실행 상태 (ACTIVE, PAUSED)

    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_seed_type", nullable = false, length = 20)
    private StrategyCycleSeedType cycleSeedType; // 사이클 종료 후 자동 재등록 정책

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}
