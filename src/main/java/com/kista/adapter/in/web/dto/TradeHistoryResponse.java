package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.tradingcycle.TradingCycle.Ticker;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record TradeHistoryResponse(
        @Schema(description = "거래 내역 고유 ID", example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890")
        UUID id,
        @Schema(description = "거래 날짜", example = "2025-01-15")
        LocalDate tradeDate,
        @Schema(description = "거래 종목", example = "SOXL")
        Ticker ticker,
        @Schema(description = "주문 유형 (LOC/MOC/LIMIT)", example = "LOC")
        Order.OrderType orderType,
        @Schema(description = "매매 방향 (BUY/SELL)", example = "BUY")
        Order.OrderDirection direction,
        @Schema(description = "주문 수량", example = "5")
        int quantity,
        @Schema(description = "주문 단가 (USD)", example = "85.50")
        BigDecimal price,
        @Schema(description = "주문 상태 (PLACED/FAILED)", example = "PLACED")
        Order.OrderStatus status,
        @Schema(description = "증권사 주문번호", example = "0000123456")
        String orderId
) {
    public static TradeHistoryResponse from(Order o) {
        return new TradeHistoryResponse(
                o.id(), o.tradeDate(), o.ticker(),
                o.orderType(), o.direction(), o.quantity(), o.price(),
                o.status(), o.kisOrderId());
    }
}
