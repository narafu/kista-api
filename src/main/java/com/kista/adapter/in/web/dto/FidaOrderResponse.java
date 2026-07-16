package com.kista.adapter.in.web.dto;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.privacy.FidaOrderCommand;
import com.kista.domain.model.strategy.Strategy.Ticker;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FidaOrderResponse(
        UUID id,                            // 생성된 master record ID
        LocalDate tradeDate,
        Ticker ticker,
        BigDecimal currentCycleStart,
        BigDecimal currentCycleRealizedPnl,
        BigDecimal avgPrice,
        int holdings,
        List<OrderItem> orders
) {
    public record OrderItem(
            String direction,
            String orderType,
            Integer quantity,
            BigDecimal price
    ) {
        static OrderItem from(Order order) {
            return new OrderItem(
                    order.direction().name(),
                    order.orderType().name(),
                    order.quantity(),
                    order.price()
            );
        }
    }

    public static FidaOrderResponse of(UUID id, FidaOrderCommand command) {
        return new FidaOrderResponse(
                id,
                command.tradeDate(),
                command.ticker(),
                command.currentCycleStart(),
                command.currentCycleRealizedPnl(),
                command.avgPrice(),
                command.holdings(),
                command.orders() == null ? List.of() : command.orders().stream().map(OrderItem::from).toList()
        );
    }
}
