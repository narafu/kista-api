package com.kista.trading.adapter.out.persistence;

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
@Table(name = "strategy_vr_version", schema = "kista")
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

    @Column(name = "initial_gradient", nullable = false)
    private int initialGradient; // 램프 시작 시점(경과 0주)의 gradient(G) 값

    @Column(name = "g_grace_weeks", nullable = false)
    private int gGraceWeeks; // gradient 램프 유예 주수

    @Column(name = "g_step_weeks", nullable = false)
    private int gStepWeeks; // gradient 램프 단계 주기(주)

    @Column(name = "g_max", nullable = false)
    private int gMax; // gradient 램프 상한값

    @Column(name = "initial_pool_limit_rate", nullable = false, precision = 6, scale = 2)
    private BigDecimal initialPoolLimitRate; // 램프 시작 시점(경과 0주)의 poolLimitRate 값

    @Column(name = "p_grace_weeks", nullable = false)
    private int pGraceWeeks; // poolLimitRate 램프 유예 주수

    @Column(name = "p_step_weeks", nullable = false)
    private int pStepWeeks; // poolLimitRate 램프 단계 주기(주)

    @Column(name = "pool_limit_floor", nullable = false, precision = 6, scale = 2)
    private BigDecimal poolLimitFloor; // poolLimitRate 램프 하한값
}
