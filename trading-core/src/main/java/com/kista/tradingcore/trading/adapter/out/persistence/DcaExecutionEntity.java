package com.kista.tradingcore.trading.adapter.out.persistence;

import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import com.kista.tradingcore.trading.domain.model.DcaExecution;
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
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "dca_execution", schema = "trading")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class DcaExecutionEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id; // PK

    @Column(name = "strategy_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyId; // FK → strategy.id (ON DELETE CASCADE)

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate; // KST 거래일

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DcaExecution.Status status; // ORDERED | SKIPPED

    @Enumerated(EnumType.STRING)
    @Column(name = "skip_reason", length = 30)
    private DcaSkipReason skipReason; // SKIPPED일 때만

    @Column(name = "order_id", columnDefinition = "UUID")
    private UUID orderId; // ORDERED일 때 orders.id

    @Column(name = "quantity", precision = 20, scale = 8)
    private BigDecimal quantity; // 계획 수량

    @Column(name = "reference_price", precision = 12, scale = 2)
    private BigDecimal referencePrice; // 판정에 쓴 전일 종가

    @Column(name = "detail", length = 500)
    private String detail; // 사람용 사유 상세
}
