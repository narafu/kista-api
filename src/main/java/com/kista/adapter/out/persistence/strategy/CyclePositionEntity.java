package com.kista.adapter.out.persistence.strategy;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cycle_position")
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CyclePositionEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "strategy_cycle_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyCycleId; // FK → strategy_cycle.id (ON DELETE CASCADE)

    @Column(name = "usd_deposit", nullable = false, precision = 20, scale = 2)
    private BigDecimal usdDeposit; // 통합주문가능금액 (매매 공식 B 기준)

    @Column(name = "closing_price", precision = 12, scale = 2)
    private BigDecimal closingPrice; // 종가 (PRIVACY 또는 초기 등록 시 null)

    @Column(name = "avg_price", precision = 20, scale = 2)
    private BigDecimal avgPrice; // 평균 매입 단가 (holdings=0이면 null)

    @Column(name = "holdings", nullable = false)
    private int holdings; // 보유 수량 (양의 정수, 단주 단위)

    @Column(name = "deleted_at")
    private Instant deletedAt; // null이면 활성, non-null이면 소프트 삭제됨
}
