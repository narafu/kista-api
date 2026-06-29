package com.kista.adapter.out.persistence.strategy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "strategy_infinite")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyInfiniteEntity {

    @Id
    @Column(name = "strategy_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyId; // FK → strategy.id (ON DELETE CASCADE)

    @Column(name = "division_count", nullable = false)
    private int divisionCount; // INFINITE 전략 분할 수 SSOT
}
