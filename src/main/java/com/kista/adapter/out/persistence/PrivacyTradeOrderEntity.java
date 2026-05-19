package com.kista.adapter.out.persistence;

import com.kista.domain.model.Order;
import com.kista.domain.model.Ticker;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "privacy_trade_order")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class PrivacyTradeOrderEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn
    private PrivacyTradeEntity privacyTrade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Order.OrderDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Order.OrderType orderType;

    @Column(nullable = false)
    private int qty;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal price;
}
