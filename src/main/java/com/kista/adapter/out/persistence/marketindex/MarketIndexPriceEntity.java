package com.kista.adapter.out.persistence.marketindex;

import com.kista.adapter.out.persistence.BaseCreatedAtEntity;
import com.kista.domain.model.stats.IndexPrice;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "market_index_prices",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_market_index_prices_symbol_date",
        columnNames = {"symbol", "trade_date"}
    )
)
@Getter
@NoArgsConstructor
class MarketIndexPriceEntity extends BaseCreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "symbol", nullable = false, length = 10)
    private String symbol;

    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "close_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal closePrice;

    static MarketIndexPriceEntity from(IndexPrice price) {
        MarketIndexPriceEntity entity = new MarketIndexPriceEntity();
        entity.symbol = price.symbol();
        entity.tradeDate = price.tradeDate();
        entity.closePrice = price.close();
        return entity;
    }

    IndexPrice toDomain() {
        return new IndexPrice(symbol, tradeDate, closePrice);
    }
}
