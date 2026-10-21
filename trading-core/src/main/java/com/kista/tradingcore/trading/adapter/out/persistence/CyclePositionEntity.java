package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cycle_position", schema = "trading")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CyclePositionEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "strategy_cycle_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyCycleId; // FK → strategy_cycle.id (ON DELETE CASCADE)

    @Column(name = "usd_deposit", nullable = false, precision = 20, scale = 2)
    private BigDecimal usdDeposit; // 통합주문가능금액 (매매 공식 B 기준)

    @Column(name = "closing_price", precision = 12, scale = 2)
    private BigDecimal closingPrice; // 종가

    @Column(name = "avg_price", precision = 20, scale = 2)
    private BigDecimal avgPrice; // 평균 매입 단가 (holdings=0이면 null)

    @Column(name = "holdings", nullable = false, precision = 20, scale = 8)
    private BigDecimal holdings; // 보유 수량 (소수 가능)

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}
