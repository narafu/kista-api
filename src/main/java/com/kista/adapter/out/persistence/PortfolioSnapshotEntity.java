package com.kista.adapter.out.persistence;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "portfolio_snapshots")
class PortfolioSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(nullable = false)
    private int qty;

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

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected PortfolioSnapshotEntity() {}

    PortfolioSnapshotEntity(UUID id, LocalDate snapshotDate, String symbol, int qty,
                            BigDecimal avgPrice, BigDecimal currentPrice,
                            BigDecimal marketValueUsd, BigDecimal usdDeposit,
                            BigDecimal totalAssetUsd) {
        this.id = id;
        this.snapshotDate = snapshotDate;
        this.symbol = symbol;
        this.qty = qty;
        this.avgPrice = avgPrice;
        this.currentPrice = currentPrice;
        this.marketValueUsd = marketValueUsd;
        this.usdDeposit = usdDeposit;
        this.totalAssetUsd = totalAssetUsd;
    }

    UUID getId() { return id; }
    LocalDate getSnapshotDate() { return snapshotDate; }
    String getSymbol() { return symbol; }
    int getQty() { return qty; }
    BigDecimal getAvgPrice() { return avgPrice; }
    BigDecimal getCurrentPrice() { return currentPrice; }
    BigDecimal getMarketValueUsd() { return marketValueUsd; }
    BigDecimal getUsdDeposit() { return usdDeposit; }
    BigDecimal getTotalAssetUsd() { return totalAssetUsd; }
    Instant getCreatedAt() { return createdAt; }
}
