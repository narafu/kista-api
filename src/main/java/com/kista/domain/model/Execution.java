package com.kista.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Execution(
        LocalDate tradeDate,
        String symbol,
        Order.OrderDirection direction,
        int qty,
        BigDecimal price,
        BigDecimal amountUsd,
        String kisOrderId
) {}
