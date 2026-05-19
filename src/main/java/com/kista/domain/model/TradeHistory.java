package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TradeHistory(
        UUID id,
        LocalDate tradeDate,
        Ticker ticker,            // 거래 종목
        String strategy,
        Order.OrderType orderType,
        Order.OrderDirection direction,
        int qty,
        BigDecimal price,
        BigDecimal amountUsd,
        Order.OrderStatus status,
        String kisOrderId,
        UUID accountId,  // FK → accounts(id), V8에서 추가 (nullable)
        Instant createdAt
) {}
