package com.kista.domain.strategy;

import com.kista.domain.model.Execution;
import com.kista.domain.model.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CorrectionStrategy {

    public List<Order> correct(List<Order> mainOrders, List<Execution> executions, LocalDate tradeDate) {
        Set<String> filledOrderIds = executions.stream()
                .map(Execution::kisOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return mainOrders.stream()
                .filter(o -> o.status() == Order.OrderStatus.PLACED)
                .filter(o -> o.kisOrderId() == null || !filledOrderIds.contains(o.kisOrderId()))
                .map(o -> new Order(
                        tradeDate,
                        o.symbol(),
                        Order.OrderType.LIMIT,
                        o.direction(),
                        o.qty(),
                        o.price(),
                        Order.OrderStatus.PLACED,
                        null,
                        o.phase()
                ))
                .toList();
    }
}
