package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Order;
import com.kista.domain.model.TradeHistory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TradeHistoryResponse(
        UUID id, LocalDate tradeDate, String symbol, String strategy,
        Order.OrderType orderType, Order.OrderDirection direction,
        int qty, BigDecimal price, BigDecimal amountUsd,
        Order.OrderStatus status, String kisOrderId, Instant createdAt
) {
    public static TradeHistoryResponse from(TradeHistory h) {
        return new TradeHistoryResponse(
                h.id(), h.tradeDate(), h.symbol(), h.strategy(),
                h.orderType(), h.direction(), h.qty(), h.price(),
                h.amountUsd(), h.status(), h.kisOrderId(), h.createdAt());
    }
}
