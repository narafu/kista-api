package com.kista.application.service.trading;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.tradingcycle.TradingCycle;
import com.kista.domain.port.out.OrderPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static java.math.RoundingMode.HALF_UP;

// BUY PLANNED 가격이 currentPrice × 1.10 초과 시 — 전략 공식 기반으로 가격 캡 적용 후 재저장
// K = position.unitAmount() (실값, 근사 아님), r = ticker.getTargetProfitRate()
// min(원래가격, cap)으로 평단가·기준가를 각각 캡핑 → 공식(K/2/A, (K-A·q1)·(1+r)/G)으로 수량 재산정
// 동일 가격으로 캡되면 단일 주문으로 병합
@Component
@RequiredArgsConstructor
@Slf4j
class BuyOrderPriceCapper {

    // 가격 캡 배수: currentPrice × 1.10 초과 시 보정 대상
    private static final BigDecimal PRICE_CAP_MULTIPLIER = new BigDecimal("1.10");

    private final OrderPort orderPort;
    private final TradingOrderPlanner orderPlanner;

    void capIfNeeded(LocalDate today, TradingCycle cycle, Account account,
                     BigDecimal currentPrice, InfinitePosition position) {
        List<Order> buyOrders = orderPort.findPlannedByAccountAndDate(account.id(), today)
                .stream().filter(o -> o.direction() == BUY).toList();
        if (buyOrders.isEmpty()) return;

        BigDecimal cap = currentPrice.multiply(PRICE_CAP_MULTIPLIER).setScale(2, HALF_UP);
        if (buyOrders.stream().noneMatch(o -> o.price().compareTo(cap) > 0)) return;

        log.info("[{}] BUY 가격 보정 필요 — cap={}, 원래 주문: {}", account.nickname(), cap,
                buyOrders.stream().map(o -> o.price() + "×" + o.quantity()).toList());

        List<Order> newBuys = recomputeBuys(today, cycle, buyOrders, cap, position);

        // 기존 BUY PLANNED 삭제 → 보정된 BUY 재저장 (수량 0 보정 포함 단일 경로)
        orderPort.deletePlannedBuyByAccountAndDate(account.id(), today);
        if (newBuys.isEmpty()) {
            log.warn("[{}] 보정 후 BUY 주문 없음 — 매수 제외", account.nickname());
            return;
        }
        orderPlanner.savePlannedOrders(newBuys, account);
        log.info("[{}] BUY 가격 보정 완료 — 보정 주문: {}", account.nickname(),
                newBuys.stream().map(o -> o.price() + "×" + o.quantity()).toList());
    }

    // 캡가격 기준 매수 수량 재산정 — 순수 계산(I/O 없음), capIfNeeded가 결과를 영속화
    private List<Order> recomputeBuys(LocalDate today, TradingCycle cycle, List<Order> buyOrders,
                                       BigDecimal cap, InfinitePosition position) {
        BigDecimal k = position.unitAmount();                            // 단위금액 (실값)

        // 원래 BUY 주문의 가격 순서: buy①=averagePrice(또는 currentPrice), buy②=referencePrice
        // 주문이 1건이면 단순 캡 적용, 2건이면 각각 캡
        if (buyOrders.size() == 1) {
            // 후반 단일 LOC 매수 케이스
            Order orig = buyOrders.getFirst();
            BigDecimal cappedPrice = orig.price().min(cap);
            int quantity = InfinitePosition.lateBuyQty(k, cappedPrice);
            return quantity > 0
                    ? List.of(plannedBuy(today, cycle, orig.orderType(), quantity, cappedPrice))
                    : List.of();
        }

        // 전반 2건: buy①(averagePrice 기반), buy②(referencePrice 기반)
        Order buy1 = buyOrders.get(0);
        Order buy2 = buyOrders.get(1);
        BigDecimal cappedAvg = buy1.price().min(cap);
        BigDecimal cappedRef = buy2.price().min(cap);

        int quantity1 = InfinitePosition.earlyBuyQty1(k, cappedAvg);
        int quantity2 = InfinitePosition.earlyBuyQty2(k, cappedAvg, quantity1, cappedRef,
                cycle.ticker().getTargetProfitRate());

        List<Order> newBuys = new ArrayList<>();
        if (quantity1 > 0) {
            // cappedAvg == cappedRef이면 병합
            if (cappedAvg.compareTo(cappedRef) == 0) {
                int merged = quantity1 + (quantity2 > 0 ? quantity2 : 0);
                newBuys.add(plannedBuy(today, cycle, buy1.orderType(), merged, cappedAvg));
            } else {
                newBuys.add(plannedBuy(today, cycle, buy1.orderType(), quantity1, cappedAvg));
                if (quantity2 > 0) {
                    newBuys.add(plannedBuy(today, cycle, buy2.orderType(), quantity2, cappedRef));
                }
            }
        } else if (quantity2 > 0) {
            newBuys.add(plannedBuy(today, cycle, buy2.orderType(), quantity2, cappedRef));
        }
        return newBuys;
    }

    private Order plannedBuy(LocalDate today, TradingCycle cycle, Order.OrderType orderType,
                             int quantity, BigDecimal price) {
        return new Order(null, null, today, cycle.ticker(),
                orderType, BUY, quantity, price, Order.OrderStatus.PLANNED, null);
    }
}
