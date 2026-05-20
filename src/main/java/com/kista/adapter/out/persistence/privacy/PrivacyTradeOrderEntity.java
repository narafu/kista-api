package com.kista.adapter.out.persistence.privacy;

import com.kista.domain.model.order.Order;
import com.kista.adapter.out.persistence.BaseAuditEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "privacy_trades_detail")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class PrivacyTradeOrderEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "privacy_trade_id", nullable = false)
    private PrivacyTradeEntity privacyTrade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private Order.OrderDirection direction;    // BUY / SELL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Order.OrderType orderType;         // LOC / MOC / LIMIT

    @Column(nullable = false)
    private int qty;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal price;
}
