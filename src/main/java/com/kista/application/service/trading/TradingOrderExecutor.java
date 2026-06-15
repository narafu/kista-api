package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.port.out.OrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

// 브로커 접수: BUY 가격 보정 → PLANNED 일괄 접수 → PLACED 마킹
@Component
@RequiredArgsConstructor
@Slf4j
class TradingOrderExecutor {

    private final OrderPort orderPort;
    private final BrokerOrderRouter brokerOrderRouter;
    private final BuyOrderPriceCapper buyOrderPriceCapper;

    // 지정된 주문 목록만 KIS 접수 (개장 잡 매도 선접수용 — BUY 보정 없음)
    List<Order> placeGiven(List<Order> orders, Account account) {
        if (orders.isEmpty()) return List.of();
        List<Order> placed = orders.stream().map(p -> {
            Order placedOrder = brokerOrderRouter.place(p, account);
            orderPort.markPlaced(p.id(), placedOrder.externalOrderId());
            return new Order(p.id(), p.accountId(), p.strategyCycleId(), p.tradeDate(), p.ticker(),
                    p.orderType(), p.direction(), p.quantity(), p.price(),
                    Order.OrderStatus.PLACED, placedOrder.externalOrderId(), null, null);
        }).toList();
        log.info("[{}] 주문 {}건 선접수", account.nickname(), placed.size());
        return placed;
    }

    // position이 있고 currentPrice가 있을 때만 보정 (수동 선행 주문은 그대로 접수)
    List<Order> placeOrders(LocalDate today, Account account, UUID strategyCycleId,
                            BigDecimal currentPrice, InfinitePosition position) {
        if (currentPrice != null && position != null) {
            buyOrderPriceCapper.capIfNeeded(today, account, strategyCycleId, currentPrice, position);
        }
        List<Order> planned = orderPort.findPlannedByCycleAndDate(strategyCycleId, today);
        List<Order> placed = planned.stream().map(p -> {
            Order placedOrder = brokerOrderRouter.place(p, account);
            orderPort.markPlaced(p.id(), placedOrder.externalOrderId()); // 접수 완료 즉시 기록
            // KIS 응답 Order는 id=null — DB PK(p.id())를 살려서 반환 (취소 API에서 사용)
            return new Order(p.id(), p.accountId(), p.strategyCycleId(), p.tradeDate(), p.ticker(),
                    p.orderType(), p.direction(), p.quantity(), p.price(),
                    Order.OrderStatus.PLACED, placedOrder.externalOrderId(), null, null);
        }).toList();
        log.info("[{}] 주문 {}건 접수", account.nickname(), placed.size());
        return placed;
    }
}
