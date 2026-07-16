package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StrategyOrdersResponse(
        @Schema(description = "전략의 주문 목록")
        List<Item> orders) {

    public record Item(
            @Schema(description = "주문 고유 ID")
            UUID id,
            @Schema(description = "매매일 (KST 기준)")
            LocalDate tradeDate,
            @Schema(description = "매수/매도 방향", example = "BUY")
            String direction,
            @Schema(description = "주문 유형", example = "LOC")
            String orderType,
            @Schema(description = "주문 수량")
            int quantity,
            @Schema(description = "주문 가격")
            BigDecimal price,
            @Schema(description = "주문 상태", example = "PLACED")
            String status,
            @Schema(description = "체결 수량 (미체결이면 null)")
            Integer filledQuantity,
            @Schema(description = "체결 가격 (미체결이면 null)")
            BigDecimal filledPrice
    ) {
        public static Item from(Order o) {
            return new Item(
                    o.id(), o.tradeDate(),
                    o.direction().name(), o.orderType().name(),
                    o.quantity(), o.price(), o.status().name(),
                    o.filledQuantity(), o.filledPrice()
            );
        }
    }

    public static StrategyOrdersResponse from(List<Order> orders) {
        return new StrategyOrdersResponse(orders.stream().map(Item::from).toList());
    }
}
