package com.kista.adapter.out.persistence.trade;

import com.kista.adapter.out.persistence.BaseAuditEntity;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Ticker;
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
@Setter(AccessLevel.PACKAGE) // markPlaced 전용 (status, kisOrderId)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class OrderEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "UUID")
    private UUID accountId; // FK → accounts.id

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private Ticker ticker;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private Order.OrderType orderType; // VARCHAR, 네이티브 PostgreSQL ENUM 아님

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Order.OrderDirection direction;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Order.OrderStatus status; // PLANNED / PLACED / FILLED / FAILED

    @Column(name = "kis_order_id", length = 30)
    private String kisOrderId; // PLACED 이후 설정
}
