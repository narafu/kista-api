package com.kista.domain.strategy;

import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.order.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderStatus.PLACED;
import static com.kista.domain.model.order.Order.OrderType.*;

@Component
public class InfiniteStrategy implements TradingStrategy {

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

    private void buildEarlyStageOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        // LOC 매수 ① — 평단가 기준
        int buyQuantity1 = position.calcEarlyBuyQuantityByAvgPrice();
        if (buyQuantity1 >= 1) {
            orders.add(new Order(tradeDate, position.ticker(), LOC, BUY, buyQuantity1, position.averagePrice(), PLACED, null));
        }

        // LOC 매수 ② — 기준가 기준
        int buyQuantity2 = position.calcEarlyBuyQuantityByRefPrice();
        if (buyQuantity2 >= 1) {
            orders.add(new Order(tradeDate, position.ticker(), LOC, BUY, buyQuantity2, position.referencePrice(), PLACED, null));
        }

        addCommonSellOrders(orders, position, tradeDate);
    }

    private void buildLateStageOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        if (position.isDepositDeficient()) {
            int mocSellQuantity = position.calcMocSellQuantity();
            if (mocSellQuantity >= 1) {
                orders.add(new Order(tradeDate, position.ticker(), MOC, SELL, mocSellQuantity, BigDecimal.ZERO, PLACED, null));
            }
        } else {
            // LOC 매수 — 기준가 기준
            int buyQuantity = position.calcLateBuyQuantity();
            if (buyQuantity >= 1) {
                orders.add(new Order(tradeDate, position.ticker(), LOC, BUY, buyQuantity, position.referencePrice(), PLACED, null));
            }

            addCommonSellOrders(orders, position, tradeDate);
        }
    }

    private void addCommonSellOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        // LOC 매도 (기준가 + 0.01)
        int locSellQuantity = position.calcLocSellQuantity();
        if (locSellQuantity >= 1) {
            BigDecimal locSellPrice = position.referencePrice().add(new BigDecimal("0.01"));
            orders.add(new Order(tradeDate, position.ticker(), LOC, SELL, locSellQuantity, locSellPrice, PLACED, null));
        }

        // 지정가 매도 (목표가)
        int limitSellQuantity = position.calcLimitSellQuantity();
        if (limitSellQuantity >= 1) {
            orders.add(new Order(tradeDate, position.ticker(), LIMIT, SELL, limitSellQuantity, position.targetPrice(), PLACED, null));
        }
    }
}