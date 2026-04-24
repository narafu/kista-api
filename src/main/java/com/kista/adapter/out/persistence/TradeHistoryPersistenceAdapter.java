package com.kista.adapter.out.persistence;

import com.kista.domain.model.TradeHistory;
import com.kista.domain.port.out.TradeHistoryPort;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
public class TradeHistoryPersistenceAdapter implements TradeHistoryPort {

    private final TradeHistoryJpaRepository repository;

    public TradeHistoryPersistenceAdapter(TradeHistoryJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(TradeHistory h) {
        repository.save(toEntity(h));
    }

    @Override
    public List<TradeHistory> findBy(LocalDate from, LocalDate to, String symbol) {
        return repository.findByTradeDateBetweenAndSymbol(from, to, symbol)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private TradeHistoryEntity toEntity(TradeHistory h) {
        return new TradeHistoryEntity(
                h.id(), h.tradeDate(), h.symbol(), h.strategy(),
                h.orderType(), h.direction(), h.qty(), h.price(),
                h.amountUsd(), h.status(), h.kisOrderId(), h.phase()
        );
    }

    private TradeHistory toDomain(TradeHistoryEntity e) {
        return new TradeHistory(
                e.getId(), e.getTradeDate(), e.getSymbol(), e.getStrategy(),
                e.getOrderType(), e.getDirection(), e.getQty(), e.getPrice(),
                e.getAmountUsd(), e.getStatus(), e.getKisOrderId(), e.getPhase(),
                e.getCreatedAt()
        );
    }
}
