package com.kista.domain.port.in;

import com.kista.domain.model.Order;
import com.kista.domain.model.Ticker;

import java.math.BigDecimal;

public record FidaOrderRequest(
        Ticker ticker,
        Order.OrderDirection direction,
        int qty,
        BigDecimal price
) {}
