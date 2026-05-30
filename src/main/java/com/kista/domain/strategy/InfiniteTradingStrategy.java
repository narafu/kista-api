package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;

import java.time.LocalDate;
import java.util.List;

public interface InfiniteTradingStrategy {
    List<Order> buildOrders(InfinitePosition position, LocalDate tradeDate);
}
