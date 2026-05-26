package com.kista.adapter.out.persistence.trade;

import com.kista.common.TradeDateConverter;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
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
        return repository.findByTradeDateBetweenAndTicker(
                        TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to), ticker)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<TradeHistory> findAll(LocalDate from, LocalDate to) {
        return repository.findByTradeDateBetween(TradeDateConverter.toUtc(from), TradeDateConverter.toUtc(to))
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private TradeHistoryEntity toEntity(TradeHistory h) {
        return new TradeHistoryEntity(
                h.id(), h.accountId(), TradeDateConverter.toUtc(h.tradeDate()), h.ticker(), h.strategy(), // KST → UTC DB
                h.orderType(), h.direction(), h.price(), h.quantity(),
                h.amountUsd(), h.status(), h.orderId()
        );
    }

    private TradeHistory toDomain(TradeHistoryEntity e) {
        return new TradeHistory(
                e.getId(), TradeDateConverter.toKst(e.getTradeDate()), e.getTicker(), e.getStrategy(), // UTC DB → KST 도메인
                e.getOrderType(), e.getDirection(), e.getQuantity(), e.getPrice(),
                e.getAmountUsd(), e.getStatus(), e.getOrderId(),
                e.getAccountId(), e.getCreatedAt()
        );
    }
}
