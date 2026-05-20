package com.kista.domain.port.in;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Ticker;

import java.math.BigDecimal;

public record FidaOrderRequest(
        Ticker ticker,
        Order.OrderDirection direction,
        int qty,
        BigDecimal price
) {}
