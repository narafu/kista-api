package com.kista.domain.strategy;

import com.kista.domain.model.kis.Execution;
import com.kista.domain.model.order.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CorrectionStrategy {

    public List<Order> correct(List<Order> mainOrders, List<Execution> executions, LocalDate tradeDate) {
        // 당일 실제 체결된 주문 번호 집합
        Set<String> filledOrderIds = executions.stream()
                .map(Execution::kisOrderId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return mainOrders.stream()
                // PLACED 상태인 주문만 보정 대상
                .filter(o -> o.status() == Order.OrderStatus.PLACED)
                // orderId 없거나 체결 목록에 없으면 미체결로 간주
                .filter(o -> o.kisOrderId() == null || !filledOrderIds.contains(o.kisOrderId()))
                // 보정 주문: id=null, accountId=null — 호출 직후 kisOrderPort.place()로 바로 접수
                .map(o -> new Order(
                        null, null,
                        tradeDate,
                        o.ticker(),
                        Order.OrderType.LIMIT,
                        o.direction(),
                        o.quantity(),
                        o.price(),
                        Order.OrderStatus.PLACED,
                        null
                ))
                .toList();
    }
}
