package com.kista.adapter.out.persistence.strategy;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "strategy_cycle_vr")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyCycleVrEntity extends BaseAuditEntity {

    @Id
    @Column(name = "strategy_cycle_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyCycleId; // FK → strategy_cycle.id (ON DELETE CASCADE)

    @Column(name = "value", nullable = false, precision = 20, scale = 2)
    private BigDecimal value; // VR 사이클 기준값

    @Column(name = "gradient", nullable = false)
    private int gradient; // VR 사이클 기울기

    @Column(name = "pool_limit", nullable = false, precision = 20, scale = 2)
    private BigDecimal poolLimit; // VR 사이클 풀 상한
}
