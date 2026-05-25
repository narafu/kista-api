package com.kista.adapter.out.persistence.trade;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "portfolio_snapshots")
@Getter
@Setter(AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class PortfolioSnapshotEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "ticker", nullable = false, length = 20)
    private Ticker ticker;

    @Column(nullable = false)
    private int holdings;

    @Column(name = "avg_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal avgPrice;

    @Column(name = "market_value_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal marketValueUsd;

    @Column(name = "usd_deposit", nullable = false, precision = 12, scale = 2)
    private BigDecimal usdDeposit;

    @Column(name = "total_asset_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAssetUsd;

    @Column(name = "account_id") // FK → accounts(id), V8에서 추가 (nullable)
    private UUID accountId;

    PortfolioSnapshotEntity(UUID id, LocalDate snapshotDate, Ticker ticker, int holdings,
                            BigDecimal avgPrice, BigDecimal marketValueUsd,
                            BigDecimal usdDeposit, BigDecimal totalAssetUsd, UUID accountId) {
        this.id = id;
        this.snapshotDate = snapshotDate;
        this.ticker = ticker;
        this.holdings = holdings;
        this.avgPrice = avgPrice;
        this.marketValueUsd = marketValueUsd;
        this.usdDeposit = usdDeposit;
        this.totalAssetUsd = totalAssetUsd;
        this.accountId = accountId;
    }
}
