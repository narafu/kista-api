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
@Table(name = "strategy_vr_version")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyVrVersionEntity extends BaseAuditEntity {

    @Id
    @Column(name = "strategy_version_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyVersionId; // FK → strategy_version.id (ON DELETE CASCADE)

    @Column(name = "interval_weeks", nullable = false)
    private int intervalWeeks; // VR 전략 리밸런싱 주기 (주)

    @Column(name = "band_width", nullable = false, precision = 20, scale = 2)
    private BigDecimal bandWidth; // VR 전략 밴드 폭

    @Column(name = "recurring_amount", nullable = false)
    private int recurringAmount; // VR 전략 정기 적립금액
}
