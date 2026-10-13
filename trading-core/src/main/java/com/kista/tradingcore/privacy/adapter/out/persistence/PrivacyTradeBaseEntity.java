package com.kista.tradingcore.privacy.adapter.out.persistence;

import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import com.kista.shared.kernel.StrategyTicker;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
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
    schema = "trading_ref",
    uniqueConstraints = @UniqueConstraint(name = "uq_privacy_trade_bases_release_date_ticker", columnNames = {"release_date", "ticker"})
)
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA 전용
class PrivacyTradeBaseEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;            // FIDA 발행일 원본 (KST) — 거래일 아님, 변환 금지

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private StrategyTicker ticker;                    // 대상 종목 (PRIVACY는 SOXL)

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleStart;     // 현재 사이클 시작 시점의 기준 가격

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal currentCycleRealizedPnl;     // 현재 사이클 실현 수익($)

    @Column(precision = 12, scale = 2)
    private BigDecimal avgPrice;              // 보유 평단가

    @Column(nullable = false)
    private int holdings;                     // 보유 수량

    @OneToMany(mappedBy = "privacyBase", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PrivacyTradeBaseOrderEntity> orders = new ArrayList<>();
}
