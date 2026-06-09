package com.kista.adapter.out.persistence.privacy;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
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
@Table(
    name = "privacy_trade_bases",
    uniqueConstraints = @UniqueConstraint(name = "uq_privacy_trade_bases_date_ticker", columnNames = {"trade_date", "ticker"})
)
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class PrivacyTradeMasterEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;              // DB는 UTC(=US 거래일) 저장, FIDA 수신값 그대로 보존 — TradeDateConverter 경유

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private Ticker ticker;                    // 대상 종목 (PRIVACY는 SOXL)

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleStart;     // 현재 사이클 시작 시점의 기준 가격

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleRealizedPnl;     // 현재 사이클 실현 수익($)

    @Column(precision = 12, scale = 2)
    private BigDecimal avgPrice;              // 보유 평단가

    @Column(nullable = false)
    private int holdings;                     // 보유 수량

    @OneToMany(mappedBy = "privacyTrade", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrivacyTradeDetailEntity> orders = new ArrayList<>();
}
