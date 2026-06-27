package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StrategyOrdersResponse(List<Item> orders) {

    public record Item(
            UUID id,
            LocalDate tradeDate,
            String direction,
            String orderType,
            int quantity,
            BigDecimal price,
            String status,
            Integer filledQuantity,
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
