package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.platform.persistence.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.util.UUID;

@Entity
@Table(name = "strategy_dca_version", schema = "trading")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class StrategyDcaVersionEntity extends BaseAuditEntity {

    @Id
    @Column(name = "strategy_version_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyVersionId; // FK → strategy_version.id (ON DELETE CASCADE)

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 20)
    private OrderDirection direction; // 매수/매도

    @Column(name = "amount_usd", nullable = false, precision = 20, scale = 2)
    private BigDecimal amountUsd; // 회차당 고정 금액 (USD)

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 20)
    private OrderType orderType; // MOO | MOC

    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 20)
    private DcaFrequency frequency; // 실행 주기

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", length = 20)
    private DayOfWeek dayOfWeek; // WEEKLY 전용

    @Column(name = "day_of_month")
    private Integer dayOfMonth; // MONTHLY 전용 (null이면 말일)
}
