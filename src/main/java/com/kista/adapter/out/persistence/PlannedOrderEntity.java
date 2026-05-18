package com.kista.adapter.out.persistence;

import com.kista.domain.model.Order;
import com.kista.domain.model.PlannedOrder;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "planned_orders")
@Getter
@Setter(AccessLevel.PACKAGE) // markExecuted 전용 (status, kisOrderId)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class PlannedOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "UUID")
    private UUID accountId; // FK → accounts.id

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private Order.OrderType orderType; // VARCHAR, 네이티브 PostgreSQL ENUM 아님

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Order.OrderDirection direction;

    @Column(nullable = false)
    private int qty;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PlannedOrder.PlannedOrderStatus status; // PENDING / EXECUTED

    @Column(name = "kis_order_id", length = 30)
    private String kisOrderId; // EXECUTED 이후 설정

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt; // DB DEFAULT now() 자동 설정
}
