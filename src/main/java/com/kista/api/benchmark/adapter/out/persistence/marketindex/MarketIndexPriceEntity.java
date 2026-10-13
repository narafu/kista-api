package com.kista.api.benchmark.adapter.out.persistence.marketindex;

import com.kista.shared.platform.persistence.BaseCreatedAtEntity;
import com.kista.api.benchmark.domain.model.IndexPrice;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
    name = "market_index_prices",
    schema = "kista_ref",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_market_index_prices_symbol_date",
        columnNames = {"symbol", "trade_date"}
    )
)
@Getter
@NoArgsConstructor
class MarketIndexPriceEntity extends BaseCreatedAtEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7) // 시간순 UUID v7 — 인덱스 끝에 append
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
