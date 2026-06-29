package com.kista.adapter.out.persistence.strategy;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "strategy_version")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyVersionEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "strategy_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyId; // FK → strategy.id

    @Column(name = "version_no", nullable = false)
    private int versionNo; // 전략 내 버전 번호

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 비활성
}
