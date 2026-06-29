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
@Table(name = "cycle_position_infinite")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CyclePositionInfiniteEntity {

    @Id
    @Column(name = "cycle_position_id", nullable = false, columnDefinition = "UUID")
    private UUID cyclePositionId; // FK → cycle_position.id (ON DELETE CASCADE)

    @Column(name = "is_reverse_mode", nullable = false)
    private boolean reverseMode; // INFINITE 포지션 리버스모드 SSOT
}
