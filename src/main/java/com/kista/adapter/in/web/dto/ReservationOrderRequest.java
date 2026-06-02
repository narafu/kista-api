package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.order.ReservationOrderCommand;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record ReservationOrderRequest(
        @Schema(description = "거래 종목", example = "SOXL")
        @NotNull Ticker ticker,
        @Schema(description = "매매 방향 (BUY 또는 SELL)", example = "BUY")
        @NotNull Order.OrderDirection direction,
        @Schema(description = "주문 수량 (양수)", example = "5")
        @Positive int quantity,
        @Schema(description = "주문 단가 USD (양수)", example = "85.50")
        @NotNull @Positive BigDecimal price
) {
    public ReservationOrderCommand toCommand() {
        return new ReservationOrderCommand(ticker, direction, quantity, price, Order.OrderType.LIMIT);
    }
}
