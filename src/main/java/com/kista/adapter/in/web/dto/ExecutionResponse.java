package com.kista.adapter.in.web.dto;

import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order.OrderDirection;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExecutionResponse(
        LocalDate tradeDate,
        Ticker ticker,
        OrderDirection direction,
        int quantity,
        BigDecimal price,
        BigDecimal amountUsd,
        String orderId
) {
    public static ExecutionResponse from(Execution e) {
        return new ExecutionResponse(
                e.tradeDate(), e.ticker(), e.direction(),
                e.quantity(), e.price(), e.amountUsd(), e.externalOrderId()
        );
    }
}
