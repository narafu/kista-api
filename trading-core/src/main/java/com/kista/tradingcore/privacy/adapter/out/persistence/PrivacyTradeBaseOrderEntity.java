package com.kista.tradingcore.privacy.adapter.out.persistence;

import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "privacy_trade_base_orders", schema = "trading_ref")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class PrivacyTradeBaseOrderEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "privacy_trade_id", nullable = false)
    private PrivacyTradeBaseEntity privacyBase;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private OrderDirection direction;    // BUY / SELL

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderType orderType;         // LOC / MOC / LIMIT

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;

    @Column
    private Integer quantity;
}
