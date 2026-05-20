package com.kista.domain.strategy;

import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.order.Order;

import java.time.LocalDate;
import java.util.List;

public interface TradingStrategy {
    List<Order> buildOrders(InfinitePosition position, LocalDate tradeDate);
}
