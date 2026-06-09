package com.kista.adapter.out.persistence.strategy;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
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
@Table(name = "strategy_cycle")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyCycleEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "strategy_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyId; // FK → strategy.id (ON DELETE CASCADE)

    @Column(name = "initial_usd_deposit", nullable = false, precision = 20, scale = 2)
    private BigDecimal initialUsdDeposit; // 이 사이클의 시작 시드(USD)

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}
