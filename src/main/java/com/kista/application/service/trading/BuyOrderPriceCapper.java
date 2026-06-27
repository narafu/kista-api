package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.port.out.OrderPort;
import com.kista.domain.strategy.InfiniteTradingStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static java.math.RoundingMode.HALF_UP;

// BUY PLANNED 가격이 currentPrice × 1.10 초과 시 — InfiniteTradingStrategy에 위임해 가격 캡 적용 후 재저장
// 가격 캡 재산정 공식(unitAmount/2/averagePrice, (unitAmount-averagePrice·q1)·(1+r)/referencePrice, 보정 주문 등)은 InfiniteStrategy.buildCappedBuyOrders 참고
@Component
@RequiredArgsConstructor
@Slf4j
class BuyOrderPriceCapper {

    // 가격 캡 배수: currentPrice × 1.10 초과 시 보정 대상
    private static final BigDecimal PRICE_CAP_MULTIPLIER = new BigDecimal("1.10");

    private final OrderPort orderPort;
    private final TradingOrderPlanner orderPlanner;
    private final InfiniteTradingStrategy infiniteStrategy;

    // PRIVACY 전용: position 없이 단순 가격 캡만 적용 (INFINITE buildCappedBuyOrders 위임 없음)
    // cap = currentPrice × 1.10 초과 BUY를 cap 가격으로 보정, 수량 유지
    void capPrivacyIfNeeded(LocalDate today, Account account, UUID strategyCycleId, BigDecimal currentPrice) {
        List<Order> buyOrders = orderPort.findPlannedByCycleAndDate(strategyCycleId, today)
                .stream().filter(o -> o.direction() == BUY).toList();
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = currentPrice.multiply(PRICE_CAP_MULTIPLIER).setScale(2, HALF_UP);
        if (buyOrders.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return;

        log.info("[{}] PRIVACY BUY 가격 보정 필요 — cap={}, 원래 주문: {}", account.nickname(), cap,
                buyOrders.stream().map(o -> o.price() + "×" + o.quantity()).toList());

        // cap 초과 BUY → cap 가격으로 교체, 나머지는 유지 (수량 변경 없음)
        List<Order> capped = buyOrders.stream()
                .map(o -> o.price().compareTo(cap) > 0 ? o.withPrice(cap) : o)
                .toList();

        orderPort.deletePlannedBuyByCycleAndDate(strategyCycleId, today);
        orderPlanner.savePlannedOrders(capped, account, strategyCycleId);
        log.info("[{}] PRIVACY BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(),
                capped.stream().map(o -> o.price() + "×" + o.quantity()).toList());
    }

    void capIfNeeded(LocalDate today, Account account, UUID strategyCycleId,
                     BigDecimal currentPrice, InfinitePosition position) {
        List<Order> buyOrders = orderPort.findPlannedByCycleAndDate(strategyCycleId, today)
                .stream().filter(o -> o.direction() == BUY).toList();
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = currentPrice.multiply(PRICE_CAP_MULTIPLIER).setScale(2, HALF_UP);
        if (buyOrders.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return;

        log.info("[{}] BUY 가격 보정 필요 — cap={}, 원래 주문: {}", account.nickname(), cap,
                buyOrders.stream().map(o -> o.price() + "×" + o.quantity()).toList());

        List<Order> newBuys = infiniteStrategy.buildCappedBuyOrders(position, today, buyOrders, cap);

        // 기존 BUY PLANNED 삭제 → 보정된 BUY 재저장 (수량 0 보정 포함 단일 경로)
        orderPort.deletePlannedBuyByCycleAndDate(strategyCycleId, today);
        if (newBuys.isEmpty()) {
            log.warn("[{}] 보정 후 BUY 주문 없음 — 매수 제외", account.nickname());
            return;
        }
        orderPlanner.savePlannedOrders(newBuys, account, strategyCycleId);
        log.info("[{}] BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(),
                newBuys.stream().map(o -> o.price() + "×" + o.quantity()).toList());
    }
}
