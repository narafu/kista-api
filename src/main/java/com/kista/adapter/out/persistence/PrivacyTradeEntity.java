package com.kista.adapter.out.persistence;

import com.kista.domain.model.Ticker;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "privacy_trades_master")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class PrivacyTradeEntity extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;              // 기준 매매표가 적용되는 거래일

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private Ticker ticker;                    // 대상 종목 (현재 PRIVACY는 SOXL 강제)

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal currentCycleStart;     // 현재 사이클 시작 시점의 기준 가격

    @Column(nullable = false, precision = 12, scale = 4)
    private BigDecimal avgPrice;              // 보유 평단가

    @Column(nullable = false)
    private int qty;                          // 보유 수량

    @OneToMany(mappedBy = "privacyTrade", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrivacyTradeOrderEntity> orders = new ArrayList<>();
}
