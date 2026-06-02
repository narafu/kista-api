package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ExecuteOrdersResponse(List<PlacedOrderItem> orders) {

    public record PlacedOrderItem(
            UUID id,
            String ticker,
            String direction,
            String orderType,
            int quantity,
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
