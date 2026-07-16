package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ExecuteOrdersResponse(
        @Schema(description = "증권사에 접수된 주문 목록")
        List<PlacedOrderItem> orders) {

    public record PlacedOrderItem(
            @Schema(description = "주문 고유 ID")
            UUID id,
            @Schema(description = "거래 종목", example = "TQQQ")
            String ticker,
            @Schema(description = "매수/매도 방향", example = "BUY")
            String direction,
            @Schema(description = "주문 유형", example = "LOC")
            String orderType,
            @Schema(description = "주문 수량")
            int quantity,
            @Schema(description = "주문 가격")
            BigDecimal price
    ) {}

    public static ExecuteOrdersResponse from(List<Order> placed) {
        return new ExecuteOrdersResponse(
                placed.stream()
                        .map(o -> new PlacedOrderItem(
                                o.id(),
                                o.ticker().name(),
                                o.direction().name(),
                                o.orderType().name(),
                                o.quantity(),
                                o.price()
                        ))
                        .toList()
        );
    }
}
