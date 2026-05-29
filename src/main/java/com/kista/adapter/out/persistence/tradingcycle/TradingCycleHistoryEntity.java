package com.kista.adapter.out.persistence.tradingcycle;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "trading_cycle_history")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class TradingCycleHistoryEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "trading_cycle_id", nullable = false, columnDefinition = "UUID")
    private UUID tradingCycleId; // FK → trading_cycle.id (UUID 간접 참조, ON DELETE CASCADE)

    @Column(name = "usd_deposit", nullable = false, precision = 20, scale = 2)
    private BigDecimal usdDeposit; // 통합주문가능금액

    @Column(name = "avg_price", precision = 20, scale = 2)
    private BigDecimal avgPrice; // 평균 매입 단가 (보유수량 0이면 null)

    @Column(name = "holdings", nullable = false)
    private int holdings; // 보유 수량 (양의 정수, 단주 단위)
}
