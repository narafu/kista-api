package com.kista.adapter.out.persistence.trade;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy.Ticker;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "orders")
@Getter
@Setter(AccessLevel.PACKAGE) // markPlaced 전용 (status, externalOrderId)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class OrderEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "UUID")
    private UUID accountId; // FK → accounts.id

    @Column(name = "strategy_cycle_id", nullable = false, columnDefinition = "UUID")
    private UUID strategyCycleId; // FK → strategy_cycle.id (멀티 전략 주문 격리)

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate; // DB는 UTC(=US 거래일) 저장, 코드는 KST로 다룸 — TradeDateConverter 경유

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private Ticker ticker;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private Order.OrderType orderType; // VARCHAR, 네이티브 PostgreSQL ENUM 아님

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Order.OrderDirection direction;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Order.OrderStatus status; // PLANNED/PLACED/FILLED/PARTIALLY_FILLED/FAILED/CANCELLED

    @Column(name = "external_order_id", length = 50)
    private String externalOrderId; // PLACED 이후 설정

    @Column(name = "filled_quantity")
    private Integer filledQuantity; // 체결 수량 (null=미확인, 0=미체결)

    @Column(name = "filled_price", precision = 12, scale = 2)
    private BigDecimal filledPrice; // 체결 가중평균가 (null=미체결)
}
