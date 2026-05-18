package com.kista.domain.strategy;

import com.kista.domain.model.InfinitePosition;
import com.kista.domain.model.Order;

import java.time.LocalDate;
import java.util.List;

public interface TradingStrategy {
    List<Order> buildOrders(InfinitePosition position, LocalDate tradeDate);
}
