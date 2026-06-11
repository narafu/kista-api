package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface InfiniteTradingStrategy {
    List<Order> buildOrders(InfinitePosition position, LocalDate tradeDate);

    // BUY PLANNED 가격이 cap을 초과할 때 cap 기준으로 매수 수량 재산정 + 보정 주문(1주 LOC × 3회) 추가
    List<Order> buildCappedBuyOrders(InfinitePosition position, LocalDate tradeDate, List<Order> buyOrders, BigDecimal cap);
}
