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
import org.hibernate.annotations.SQLRestriction;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "strategy_infinite_version")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyInfiniteEntity extends BaseAuditEntity {

    @Id
    @Column(name = "strategy_version_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyVersionId; // FK → strategy_version.id (ON DELETE CASCADE)

    @Column(name = "division_count", nullable = false)
    private int divisionCount; // INFINITE 전략 분할 수 SSOT

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}
