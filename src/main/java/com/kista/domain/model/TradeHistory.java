package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TradeHistory(
        UUID id,
        LocalDate tradeDate,
        String symbol,
        String strategy,
        Order.OrderType orderType,
        Order.OrderDirection direction,
        int qty,
        BigDecimal price,
        BigDecimal amountUsd,
        Order.OrderStatus status,
        String kisOrderId,
        String phase,
        Instant createdAt
) {}
