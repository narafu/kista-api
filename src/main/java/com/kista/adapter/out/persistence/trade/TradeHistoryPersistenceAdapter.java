package com.kista.adapter.out.persistence.trade;

import com.kista.domain.model.strategy.Ticker;
import com.kista.domain.model.order.TradeHistory;
import com.kista.domain.port.out.TradeHistoryPort;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor(access = AccessLevel.PACKAGE) // TradeHistoryJpaRepository가 package-private
public class TradeHistoryPersistenceAdapter implements TradeHistoryPort {

    private final TradeHistoryJpaRepository repository;

    @Override
    public void save(TradeHistory h) {
        repository.save(toEntity(h));
    }

    @Override
    public List<TradeHistory> findBy(LocalDate from, LocalDate to, Ticker ticker) {
        return repository.findByTradeDateBetweenAndTicker(from, to, ticker)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<TradeHistory> findAll(LocalDate from, LocalDate to) {
        return repository.findByTradeDateBetween(from, to)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private TradeHistoryEntity toEntity(TradeHistory h) {
        return new TradeHistoryEntity(
                h.id(), h.tradeDate(), h.ticker(), h.strategy(),
                h.orderType(), h.direction(), h.qty(), h.price(),
                h.amountUsd(), h.status(), h.kisOrderId(), h.accountId()
        );
    }

    private TradeHistory toDomain(TradeHistoryEntity e) {
        return new TradeHistory(
                e.getId(), e.getTradeDate(), e.getTicker(), e.getStrategy(),
                e.getOrderType(), e.getDirection(), e.getQty(), e.getPrice(),
                e.getAmountUsd(), e.getStatus(), e.getKisOrderId(),
                e.getAccountId(), e.getCreatedAt()
        );
    }
}
