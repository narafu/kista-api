package com.kista.adapter.out.persistence.trade;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import com.kista.domain.model.strategy.Strategy.Ticker;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "portfolio_snapshots")
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

    @Column(name = "current_price", nullable = false, precision = 12, scale = 4)
    private BigDecimal currentPrice;

    @Column(name = "market_value_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal marketValueUsd;

    @Column(name = "usd_deposit", nullable = false, precision = 12, scale = 2)
    private BigDecimal usdDeposit;

    @Column(name = "total_asset_usd", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAssetUsd;

    @Column(name = "account_id") // FK → accounts(id), V8에서 추가 (nullable)
    private UUID accountId;

    protected PortfolioSnapshotEntity() {}

    PortfolioSnapshotEntity(UUID id, LocalDate snapshotDate, Ticker ticker, int holdings,
                            BigDecimal avgPrice, BigDecimal currentPrice,
                            BigDecimal marketValueUsd, BigDecimal usdDeposit,
                            BigDecimal totalAssetUsd, UUID accountId) {
        this.id = id;
        this.snapshotDate = snapshotDate;
        this.ticker = ticker;
        this.holdings = holdings;
        this.avgPrice = avgPrice;
        this.currentPrice = currentPrice;
        this.marketValueUsd = marketValueUsd;
        this.usdDeposit = usdDeposit;
        this.totalAssetUsd = totalAssetUsd;
        this.accountId = accountId;
    }

    UUID getId() { return id; }
    LocalDate getSnapshotDate() { return snapshotDate; }
    Ticker getTicker() { return ticker; }
    int getHoldings() { return holdings; }
    BigDecimal getAvgPrice() { return avgPrice; }
    BigDecimal getCurrentPrice() { return currentPrice; }
    BigDecimal getMarketValueUsd() { return marketValueUsd; }
    BigDecimal getUsdDeposit() { return usdDeposit; }
    BigDecimal getTotalAssetUsd() { return totalAssetUsd; }
    UUID getAccountId() { return accountId; }
}
