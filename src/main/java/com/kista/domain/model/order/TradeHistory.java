package com.kista.domain.model.order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;

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
        int quantity,
        BigDecimal price,
        BigDecimal amountUsd,
        Order.OrderStatus status,
        String orderId,
        UUID accountId,  // FK → accounts(id), V8 추가·V36에서 NOT NULL 강제
        Instant createdAt
) {}
