package com.kista.adapter.out.persistence.strategy;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
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
@Table(name = "cycle_position_infinite")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CyclePositionInfiniteEntity extends BaseCreatedAtEntity {

    @Id
    @Column(name = "cycle_position_id", nullable = false, columnDefinition = "UUID")
    private UUID cyclePositionId; // FK → cycle_position.id (ON DELETE CASCADE)

    @Column(name = "is_reverse_mode", nullable = false)
    private boolean reverseMode; // INFINITE 포지션 리버스모드 SSOT

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}
