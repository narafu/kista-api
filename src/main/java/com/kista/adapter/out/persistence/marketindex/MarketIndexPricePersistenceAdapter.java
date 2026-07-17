package com.kista.adapter.out.persistence.marketindex;

import com.kista.domain.model.stats.IndexPrice;
import com.kista.domain.port.out.IndexPricePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class MarketIndexPricePersistenceAdapter implements IndexPricePort {

    private final MarketIndexPriceJpaRepository repository;

    @Override
    public List<IndexPrice> findBySymbolAndRange(String symbol, LocalDate from, LocalDate to) {
        return repository.findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, from, to)
                .stream().map(MarketIndexPriceEntity::toDomain).toList();
    }

    @Override
    public Optional<LocalDate> findMaxTradeDate(String symbol) {
        return repository.findTop1BySymbolOrderByTradeDateDesc(symbol)
                .map(MarketIndexPriceEntity::getTradeDate);
    }

    @Override
    public void saveAll(List<IndexPrice> prices) {
        repository.saveAll(prices.stream().map(MarketIndexPriceEntity::from).toList());
    }
}
