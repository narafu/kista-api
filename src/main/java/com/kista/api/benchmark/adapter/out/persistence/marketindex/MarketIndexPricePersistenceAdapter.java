package com.kista.api.benchmark.adapter.out.persistence.marketindex;

import com.kista.api.benchmark.domain.model.IndexPrice;
import com.kista.api.benchmark.domain.model.SplitAdjustment;
import com.kista.api.benchmark.application.port.output.IndexPricePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    @Transactional
    public void saveAll(List<IndexPrice> prices) {
        List<MarketIndexPriceEntity> inserts = new ArrayList<>();
        prices.stream().collect(Collectors.groupingBy(IndexPrice::symbol)).forEach((symbol, group) -> {
            // 같은 구간의 기존 행을 날짜로 찾아 있으면 덮어쓰고(dirty checking) 없으면 새로 넣는다
            LocalDate from = group.stream().map(IndexPrice::tradeDate).min(LocalDate::compareTo).orElseThrow();
            LocalDate to = group.stream().map(IndexPrice::tradeDate).max(LocalDate::compareTo).orElseThrow();
            Map<LocalDate, MarketIndexPriceEntity> existing = repository
                    .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(symbol, from, to).stream()
                    .collect(Collectors.toMap(MarketIndexPriceEntity::getTradeDate, Function.identity()));
            for (IndexPrice price : group) {
                MarketIndexPriceEntity entity = existing.get(price.tradeDate());
                if (entity != null) {
                    entity.changeClose(price.close());
                } else {
                    MarketIndexPriceEntity created = MarketIndexPriceEntity.from(price);
                    inserts.add(created);
                    existing.put(price.tradeDate(), created); // 입력에 같은 날짜가 또 오면 덮어쓰기로 처리
                }
            }
        });
        repository.saveAll(inserts);
    }

    @Override
    @Transactional
    public int applySplit(String symbol, SplitAdjustment adjustment) {
        return repository.scaleCloses(symbol, adjustment.through(),
                BigDecimal.valueOf(adjustment.numerator()), BigDecimal.valueOf(adjustment.denominator()));
    }
}
