package com.kista.trading.adapter.out.persistence;

import com.kista.platform.persistence.BaseCreatedAtEntity;
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
@Table(name = "strategy_version", schema = "trading")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyVersionEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "strategy_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyId; // FK → strategy.id

    @Column(name = "version_no", nullable = false)
    private int versionNo; // 전략 내 버전 번호

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 비활성
}
