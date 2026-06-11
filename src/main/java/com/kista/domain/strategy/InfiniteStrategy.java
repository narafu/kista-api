package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderType.*;

@Component
class InfiniteStrategy implements InfiniteTradingStrategy {

    // 가격 캡 보정 주문 추가 횟수
    private static final int CORRECTION_ORDER_COUNT = 3;

    @Override
    public List<Order> buildOrders(InfinitePosition position, LocalDate tradeDate) {
        List<Order> orders = new ArrayList<>();
        if (position.isEarlyStage()) {
            buildEarlyStageOrders(orders, position, tradeDate);
        } else {
            buildLateStageOrders(orders, position, tradeDate);
        }
        return orders;
    }

    @Override
    public List<Order> buildCappedBuyOrders(InfinitePosition position, LocalDate tradeDate, List<Order> buyOrders, BigDecimal cap) {
        BigDecimal unitAmount = position.unitAmount(); // 단위금액 (실값)

        // 원래 BUY 주문의 가격 순서: buy①=averagePrice(또는 currentPrice), buy②=referencePrice
        // 주문이 1건이면 후반 단일 LOC, 2건이면 전반 ①②
        List<Order> newBuys = buyOrders.size() == 1
                ? computeLateBuys(tradeDate, position, buyOrders.getFirst(), cap, unitAmount)
                : computeEarlyBuys(tradeDate, position, buyOrders, cap, unitAmount);

        // 전후반 공통 보정 주문
        for (int i = 0; i < CORRECTION_ORDER_COUNT; i++) {
            addCorrectionOrder(tradeDate, position, newBuys, unitAmount);
        }

        return newBuys;
    }

    // 후반 단일 LOC 매수: K/G
    private List<Order> computeLateBuys(LocalDate tradeDate, InfinitePosition position, Order orig, BigDecimal cap, BigDecimal unitAmount) {
        BigDecimal cappedPrice = orig.price().min(cap);
        int quantity = InfinitePosition.lateBuyQty(unitAmount, cappedPrice);
        List<Order> buys = new ArrayList<>();
        if (quantity > 0) buys.add(Order.planned(tradeDate, position.ticker(), orig.orderType(), BUY, quantity, cappedPrice));
        return buys;
    }

    // 전반 2건: buy①(averagePrice 기반), buy②(referencePrice 기반)
    private List<Order> computeEarlyBuys(LocalDate tradeDate, InfinitePosition position, List<Order> buyOrders, BigDecimal cap, BigDecimal unitAmount) {
        Order buy1 = buyOrders.get(0);
        Order buy2 = buyOrders.get(1);
        BigDecimal cappedAvg = buy1.price().min(cap);
        BigDecimal cappedRef = buy2.price().min(cap);

        int quantity1 = InfinitePosition.earlyBuyQty1(unitAmount, cappedAvg);
        int quantity2 = InfinitePosition.earlyBuyQty2(unitAmount, cappedAvg, quantity1, cappedRef,
                position.ticker().getTargetProfitRate());

        List<Order> buys = new ArrayList<>();
        if (quantity1 > 0) {
            // cappedAvg == cappedRef이면 병합
            if (cappedAvg.compareTo(cappedRef) == 0) {
                int merged = quantity1 + (quantity2 > 0 ? quantity2 : 0);
                buys.add(Order.planned(tradeDate, position.ticker(), buy1.orderType(), BUY, merged, cappedAvg));
            } else {
                buys.add(Order.planned(tradeDate, position.ticker(), buy1.orderType(), BUY, quantity1, cappedAvg));
                if (quantity2 > 0) buys.add(Order.planned(tradeDate, position.ticker(), buy2.orderType(), BUY, quantity2, cappedRef));
            }
        } else if (quantity2 > 0) {
            buys.add(Order.planned(tradeDate, position.ticker(), buy2.orderType(), BUY, quantity2, cappedRef));
        }
        return buys;
    }

    private void addCorrectionOrder(LocalDate tradeDate, InfinitePosition position, List<Order> newBuys, BigDecimal unitAmount) {
        int totalQuantity = newBuys.stream().mapToInt(Order::quantity).sum();
        if (totalQuantity == 0) {
            return;
        }

        BigDecimal adjustedOrderPrice = unitAmount.divide(BigDecimal.valueOf(totalQuantity + 1), 2, RoundingMode.HALF_UP);

        newBuys.add(Order.planned(tradeDate, position.ticker(), LOC, BUY, 1, adjustedOrderPrice));
    }

    private void buildEarlyStageOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        // LOC 매수 ① — 평단가 기준
        int buyQuantity1 = position.calcEarlyBuyQuantityByAvgPrice();
        if (buyQuantity1 >= 1) {
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, BUY, buyQuantity1, position.averagePrice()));
        }

        // LOC 매수 ② — 기준가 기준
        int buyQuantity2 = position.calcEarlyBuyQuantityByRefPrice(buyQuantity1);
        if (buyQuantity2 >= 1) {
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, BUY, buyQuantity2, position.referencePrice()));
        }

        addCommonSellOrders(orders, position, tradeDate);
    }

    private void buildLateStageOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        if (position.isDepositDeficient()) {
            int mocSellQuantity = position.calcMocSellQuantity();
            if (mocSellQuantity >= 1) {
                orders.add(Order.planned(tradeDate, position.ticker(), MOC, SELL, mocSellQuantity, BigDecimal.ZERO));
            }
        } else {
            // LOC 매수 — 기준가 기준
            int buyQuantity = position.calcLateBuyQuantity();
            if (buyQuantity >= 1) {
                orders.add(Order.planned(tradeDate, position.ticker(), LOC, BUY, buyQuantity, position.referencePrice()));
            }

            addCommonSellOrders(orders, position, tradeDate);
        }
    }

    private void addCommonSellOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        // LOC 매도 (기준가 + 0.01)
        int locSellQuantity = position.calcLocSellQuantity();
        if (locSellQuantity >= 1) {
            BigDecimal locSellPrice = position.referencePrice().add(new BigDecimal("0.01"));
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, SELL, locSellQuantity, locSellPrice));
        }

        // 지정가 매도 (목표가)
        int limitSellQuantity = position.calcLimitSellQuantity();
        if (limitSellQuantity >= 1) {
            orders.add(Order.planned(tradeDate, position.ticker(), LIMIT, SELL, limitSellQuantity, position.targetPrice()));
        }
    }
}
