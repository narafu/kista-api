package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.strategy.CycleOrderStrategy;
import com.kista.domain.strategy.InfiniteStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static java.math.RoundingMode.HALF_UP;

// BUY PLANNED 가격이 currentPrice × 1.10 초과 시 — InfiniteStrategy에 위임해 가격 캡 적용 후 재저장
// 가격 캡 재산정 공식(unitAmount/2/averagePrice, (unitAmount-averagePrice·q1)·(1+r)/referencePrice, 보정 주문 등)은 InfiniteStrategy.buildCappedBuyOrders 참고
@Component
@RequiredArgsConstructor
@Slf4j
class BuyOrderPriceCapper {

    // 가격 캡 배수: currentPrice × 1.10 초과 시 보정 대상
    private static final BigDecimal PRICE_CAP_MULTIPLIER = new BigDecimal("1.10");

    private final OrderPort orderPort;
    private final TradingOrderPlanner orderPlanner;
    private final InfiniteStrategy infiniteStrategy;

    // 신규 후보의 최종 BUY를 allocator 입력 전에 계산하며 영속화는 수행하지 않는다
    List<Order> prepareForAllocation(List<Order> orders, BigDecimal currentPrice, InfinitePosition position,
                                     CycleOrderStrategy.PriceCapMode mode, LocalDate tradeDate) {
        if (mode == null || mode == CycleOrderStrategy.PriceCapMode.NONE || currentPrice == null) return orders;

        BigDecimal cap = currentPrice.multiply(PRICE_CAP_MULTIPLIER).setScale(2, HALF_UP);
        List<Order> buyOrders = orders.stream().filter(order -> order.direction() == BUY).toList();
        if (buyOrders.stream().noneMatch(order -> order.price().compareTo(cap) > 0)) return orders;

        if (mode == CycleOrderStrategy.PriceCapMode.PRIVACY_SIMPLE) {
            return orders.stream()
                    .map(order -> order.direction() == BUY && order.price().compareTo(cap) > 0
                            ? order.withPrice(cap)
                            : order)
                    .toList();
        }
        if (position == null) return orders;

        List<Order> cappedBuys = infiniteStrategy.buildCappedBuyOrders(position, tradeDate, buyOrders, cap);
        return replaceBuysPreservingOrder(orders, cappedBuys);
    }

    // 재산정 BUY는 원래 BUY 슬롯을 채우고, 추가 correction BUY는 기존 상대 순서 뒤에 붙인다
    private List<Order> replaceBuysPreservingOrder(List<Order> orders, List<Order> cappedBuys) {
        List<Order> prepared = new ArrayList<>(orders.size() + cappedBuys.size());
        int cappedBuyIndex = 0;
        for (Order order : orders) {
            if (order.direction() != BUY) {
                prepared.add(order);
            } else if (cappedBuyIndex < cappedBuys.size()) {
                prepared.add(cappedBuys.get(cappedBuyIndex++));
            }
        }
        prepared.addAll(cappedBuys.subList(cappedBuyIndex, cappedBuys.size()));
        return List.copyOf(prepared);
    }

    // PRIVACY 전용: cap 초과 주문만 CANCELLED → cap 가격으로 재저장 (cap 이하 주문은 그대로 유지)
    void capPrivacyIfNeeded(LocalDate today, Account account, UUID strategyCycleId, BigDecimal currentPrice) {
        List<Order> buyOrders = orderPort.findPlannedByCycleAndDate(strategyCycleId, today)
                .stream().filter(o -> o.direction() == BUY).toList();
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = currentPrice.multiply(PRICE_CAP_MULTIPLIER).setScale(2, HALF_UP);
        List<Order> exceeding = buyOrders.stream().filter(o -> o.price().compareTo(cap) > 0).toList();
        if (exceeding.isEmpty()) return;

        log.info("[{}] PRIVACY BUY 가격 보정 필요 — cap={}, 초과 주문: {}", account.nickname(), cap, describeOrders(exceeding));
        // cap 초과 주문만 CANCELLED 처리 → cap 가격으로 재저장
        exceeding.forEach(o -> orderPort.markCancelled(o.id()));
        List<Order> corrected = exceeding.stream().map(o -> o.withPrice(cap)).toList();
        orderPlanner.savePlannedOrders(corrected, account, strategyCycleId);
        log.info("[{}] PRIVACY BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(), describeOrders(corrected));
    }

    // INFINITE 전용: position 기반 수량 재산정 + 보정 주문 포함
    void capIfNeeded(LocalDate today, Account account, UUID strategyCycleId,
                     BigDecimal currentPrice, InfinitePosition position) {
        applyCapIfNeeded(today, account, strategyCycleId, currentPrice,
                (orders, cap) -> infiniteStrategy.buildCappedBuyOrders(position, today, orders, cap));
    }

    // 공통 cap 적용 골격: PLANNED BUY 조회 → cap 초과 확인 → 기존 주문 CANCELLED → 보정 주문 저장
    private void applyCapIfNeeded(LocalDate today, Account account, UUID strategyCycleId,
                                  BigDecimal currentPrice,
                                  BiFunction<List<Order>, BigDecimal, List<Order>> correctFn) {
        List<Order> buyOrders = orderPort.findPlannedByCycleAndDate(strategyCycleId, today)
                .stream().filter(o -> o.direction() == BUY).toList();
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = currentPrice.multiply(PRICE_CAP_MULTIPLIER).setScale(2, HALF_UP);
        if (buyOrders.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return;

        log.info("[{}] BUY 가격 보정 필요 — cap={}, 원래 주문: {}", account.nickname(), cap, describeOrders(buyOrders));

        List<Order> newBuys = correctFn.apply(buyOrders, cap);

        // 기존 BUY PLANNED CANCELLED 처리 → 보정된 BUY 재저장
        buyOrders.forEach(o -> orderPort.markCancelled(o.id()));
        if (newBuys.isEmpty()) {
            log.warn("[{}] 보정 후 BUY 주문 없음 — 매수 제외", account.nickname());
            return;
        }
        orderPlanner.savePlannedOrders(newBuys, account, strategyCycleId);
        log.info("[{}] BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(), describeOrders(newBuys));
    }

    // 주문 목록을 "가격×수량" 형식으로 표현 — 가격 보정 전후 로그용
    private static String describeOrders(List<Order> orders) {
        return orders.stream().map(o -> o.price() + "×" + o.quantity()).toList().toString();
    }
}
