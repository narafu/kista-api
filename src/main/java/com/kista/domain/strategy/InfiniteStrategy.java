package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.InfinitePosition;
import com.kista.domain.model.Order;
import com.kista.domain.model.Ticker;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.domain.model.Order.OrderDirection.BUY;
import static com.kista.domain.model.Order.OrderDirection.SELL;
import static com.kista.domain.model.Order.OrderStatus.PLACED;
import static com.kista.domain.model.Order.OrderType.*;

@Component
public class InfiniteStrategy implements TradingStrategy {

    @Override
    public List<Order> buildOrders(AccountBalance balance, BigDecimal currentPrice, Ticker ticker, LocalDate tradeDate) {
        // 1. 컨텍스트를 담은 도메인 객체 생성
        InfinitePosition position = new InfinitePosition(balance, ticker, currentPrice);
        List<Order> orders = new ArrayList<>();

        // 2. TDA 원칙에 따른 선언적 흐름 제어
        if (position.isEarlyStage()) {
            buildEarlyStageOrders(orders, position, tradeDate);
        } else {
            buildLateStageOrders(orders, position, tradeDate);
        }

        return orders;
    }

    private void buildEarlyStageOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        // LOC 매수 ① — 평단가 기준
        int buyQty1 = position.calcEarlyBuyQuantityByAvgPrice();
        if (buyQty1 >= 1) {
            orders.add(new Order(tradeDate, position.symbol(), LOC, BUY, buyQty1, position.averagePrice(), PLACED, null));
        }

        // LOC 매수 ② — 기준가 기준
        int buyQty2 = position.calcEarlyBuyQuantityByRefPrice();
        if (buyQty2 >= 1) {
            orders.add(new Order(tradeDate, position.symbol(), LOC, BUY, buyQty2, position.referencePrice(), PLACED, null));
        }

        addCommonSellOrders(orders, position, tradeDate);
    }

    private void buildLateStageOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        if (position.isDepositDeficient()) {
            int mocSellQty = position.calcMocSellQuantity();
            if (mocSellQty >= 1) {
                orders.add(new Order(tradeDate, position.symbol(), MOC, SELL, mocSellQty, BigDecimal.ZERO, PLACED, null));
            }
        } else {
            // LOC 매수 — 기준가 기준
            int buyQty = position.calcLateBuyQuantity();
            if (buyQty >= 1) {
                orders.add(new Order(tradeDate, position.symbol(), LOC, BUY, buyQty, position.referencePrice(), PLACED, null));
            }

            addCommonSellOrders(orders, position, tradeDate);
        }
    }

    private void addCommonSellOrders(List<Order> orders, InfinitePosition position, LocalDate tradeDate) {
        // LOC 매도 (기준가 + 0.01)
        int locSellQty = position.calcLocSellQuantity();
        if (locSellQty >= 1) {
            BigDecimal locSellPrice = position.referencePrice().add(new BigDecimal("0.01"));
            orders.add(new Order(tradeDate, position.symbol(), LOC, SELL, locSellQty, locSellPrice, PLACED, null));
        }

        // 지정가 매도 (목표가)
        int limitSellQty = position.calcLimitSellQuantity();
        if (limitSellQty >= 1) {
            orders.add(new Order(tradeDate, position.symbol(), LIMIT, SELL, limitSellQty, position.targetPrice(), PLACED, null));
        }
    }
}