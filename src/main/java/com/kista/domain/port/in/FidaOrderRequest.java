package com.kista.domain.port.in;

import com.kista.domain.model.Order;

import java.math.BigDecimal;

public record FidaOrderRequest(
        String symbol,
        Order.OrderDirection direction,
        int qty,
        BigDecimal price
) {}
