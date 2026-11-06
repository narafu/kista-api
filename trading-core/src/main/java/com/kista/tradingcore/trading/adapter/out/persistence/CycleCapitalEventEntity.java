package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import com.kista.tradingcore.trading.domain.model.CycleCapitalEvent;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cycle_capital_event", schema = "trading")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CycleCapitalEventEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "strategy_cycle_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyCycleId; // FK → strategy_cycle.id (ON DELETE CASCADE)

    @Column(name = "cycle_position_id", nullable = false, columnDefinition = "UUID")
    private UUID cyclePositionId; // FK → cycle_position.id (ON DELETE CASCADE)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CycleCapitalEvent.Kind kind; // INJECT | WITHDRAW

    @Column(name = "deposit_delta", nullable = false, precision = 20, scale = 2)
    private BigDecimal depositDelta; // 예수금 증감 (USD)

    @Column(name = "holdings_delta", nullable = false, precision = 20, scale = 8)
    private BigDecimal holdingsDelta; // 보유수량 증감

    @Column(name = "principal_delta", nullable = false, precision = 20, scale = 2)
    private BigDecimal principalDelta; // 원금 증감 (USD)

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt; // 변경 발생 시각
}
