package com.kista.adapter.out.persistence.strategy;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import com.kista.domain.model.strategy.StrategyCycle;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

    @Column(name = "start_amount", nullable = false, precision = 20, scale = 2)
    private BigDecimal startAmount; // 사이클 시작금액 (USD 시드)

    @Column(name = "end_amount", precision = 20, scale = 2)
    private BigDecimal endAmount; // 사이클 종료금액 (청산 후 USD, 진행 중이면 null)

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate; // 사이클 시작일자 (KST)

    @Column(name = "end_date")
    private LocalDate endDate; // 사이클 종료일자 (KST, 진행 중이면 null)

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}
