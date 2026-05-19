package com.kista.adapter.out.persistence;

import com.kista.domain.model.Order;
import com.kista.domain.model.PlannedOrder;
import com.kista.domain.model.Ticker;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "privacy_trade")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class PrivacyTradeEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private Ticker ticker;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal currentCycleStart;

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal avgPrice;

    @Column(nullable = false)
    private int qty;

    @OneToMany(mappedBy = "privacyTrade")
    private List<PrivacyTradeOrderEntity> orders = new ArrayList<>();
}
