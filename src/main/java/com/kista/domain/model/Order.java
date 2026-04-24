package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Order(
        LocalDate tradeDate,
        String symbol,
        OrderType orderType,
        OrderDirection direction,
        int qty,
        BigDecimal price,
        OrderStatus status,
        String kisOrderId,
        String phase
) {
    public enum OrderType { LOC, MOC, LIMIT }
    public enum OrderDirection { BUY, SELL }
    public enum OrderStatus { PLACED, FILLED, FAILED }
}
