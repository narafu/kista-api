package com.kista.adapter.in.web.dto;

import com.kista.domain.model.Order;
import com.kista.domain.model.ReservationOrderCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ReservationOrderRequest(
        @NotBlank String symbol,                    // 종목코드 (예: SOXL)
        @NotNull Order.OrderDirection direction,    // BUY 또는 SELL
        @Positive int qty,                          // 주문수량 (양수)
        @NotNull @Positive BigDecimal price         // 주문단가 USD (양수)
) {
    public ReservationOrderCommand toCommand() {
        return new ReservationOrderCommand(symbol, direction, qty, price);
    }
}
