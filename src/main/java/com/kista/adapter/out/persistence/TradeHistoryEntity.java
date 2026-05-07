package com.kista.adapter.out.persistence;

import com.kista.domain.model.Order;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "trade_histories")
class TradeHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false, length = 50)
    private String strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private Order.OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Order.OrderDirection direction;

    @Column(nullable = false)
    private int qty;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal price;

    @Column(name = "amount_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountUsd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Order.OrderStatus status;

    @Column(name = "kis_order_id", length = 30)
    private String kisOrderId;

    @Column(name = "account_id") // FK → accounts(id), V8에서 추가 (nullable)
    private UUID accountId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected TradeHistoryEntity() {}

    TradeHistoryEntity(UUID id, LocalDate tradeDate, String symbol, String strategy,
                       Order.OrderType orderType, Order.OrderDirection direction,
                       int qty, BigDecimal price, BigDecimal amountUsd,
                       Order.OrderStatus status, String kisOrderId, UUID accountId) {
        this.id = id;
        this.tradeDate = tradeDate;
        this.symbol = symbol;
        this.strategy = strategy;
        this.orderType = orderType;
        this.direction = direction;
        this.qty = qty;
        this.price = price;
        this.amountUsd = amountUsd;
        this.status = status;
        this.kisOrderId = kisOrderId;
        this.accountId = accountId;
    }

    UUID getId() { return id; }
    LocalDate getTradeDate() { return tradeDate; }
    String getSymbol() { return symbol; }
    String getStrategy() { return strategy; }
    Order.OrderType getOrderType() { return orderType; }
    Order.OrderDirection getDirection() { return direction; }
    int getQty() { return qty; }
    BigDecimal getPrice() { return price; }
    BigDecimal getAmountUsd() { return amountUsd; }
    Order.OrderStatus getStatus() { return status; }
    String getKisOrderId() { return kisOrderId; }
    UUID getAccountId() { return accountId; }
    Instant getCreatedAt() { return createdAt; }
}
