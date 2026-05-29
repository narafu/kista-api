package com.kista.adapter.out.persistence.trade;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "trade_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
@AllArgsConstructor(access = AccessLevel.PACKAGE)
class TradeHistoryEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, columnDefinition = "UUID") // FK → accounts(id), V8 추가·V36에서 NOT NULL 강제
    private UUID accountId;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate; // DB는 UTC(=US 거래일) 저장, 코드는 KST로 다룸 — TradeDateConverter 경유

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private Ticker ticker;

    @Column(nullable = false, length = 50)
    private String strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 10)
    private Order.OrderType orderType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Order.OrderDirection direction;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "amount_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountUsd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Order.OrderStatus status;

    @Column(name = "order_id", length = 30) // 증권사 주문번호 (broker 무관)
    private String orderId;
}
