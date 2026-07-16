package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.Strategy;
import com.kista.domain.model.strategy.VrPosition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderTiming.AT_CLOSE;
import static com.kista.domain.model.order.Order.OrderTiming.AT_OPEN;
import static com.kista.domain.model.order.Order.OrderType.LOC;
import static com.kista.domain.model.order.Order.OrderType.LIMIT;
import static java.math.RoundingMode.HALF_UP;

// VR(밸류리밸런싱) 전략 — 매수·매도 사다리 주문 생성
@Component
public class VrStrategy {

    // 매수·매도 사다리 최대 단 수
    private static final int MAX_RUNGS = 20;

    // 주문 목록 생성 — 매수 사다리(pool 허용 범위) + 매도 사다리(보유 수량 범위)
    // ticker: 주문에 기록할 거래 종목 (VrPosition은 ticker를 직접 보유하지 않음)
    // currentPrice: null이면 가격 캡 미적용
    public List<Order> buildOrders(VrPosition position, Strategy.Ticker ticker,
                                   BigDecimal currentPrice, LocalDate tradeDate) {
        if (position.firstCycle()) {
            List<Order> bootstrapOrders = buildBootstrapOrders(position, ticker, currentPrice, tradeDate);
            if (bootstrapOrders != null) return bootstrapOrders;
        }

        List<Order> orders = new ArrayList<>();
        orders.addAll(buildBuyOrders(position, ticker, currentPrice, tradeDate));
        orders.addAll(buildSellOrders(position, ticker, tradeDate));
        return orders;
    }

    // 첫 사이클 bootstrap — 기존 보유/현금 상태를 LOC 분할 주문으로 poolLimit에 맞춤
    private List<Order> buildBootstrapOrders(VrPosition position, Strategy.Ticker ticker,
                                             BigDecimal currentPrice, LocalDate tradeDate) {
        if (currentPrice == null || currentPrice.signum() <= 0) return List.of();

        boolean hasValue = position.value().signum() > 0;
        boolean hasPool = position.pool().signum() > 0;

        if (hasValue && !hasPool) {
            BigDecimal price = currentPrice.multiply(new BigDecimal("0.90")).setScale(2, HALF_UP);
            return buildDailyLocOrder(position.poolLimit(), position.remainingTradingDays(),
                    price, ticker, tradeDate, SELL);
        }
        if (!hasValue && hasPool) {
            BigDecimal price = currentPrice.multiply(new BigDecimal("1.10")).setScale(2, HALF_UP);
            return buildDailyLocOrder(position.poolLimit(), position.remainingTradingDays(),
                    price, ticker, tradeDate, BUY);
        }
        if (!hasValue && !hasPool && position.recurringAmount() > 0) {
            if (!position.cycleDue()) return List.of();
            BigDecimal price = currentPrice.multiply(new BigDecimal("1.10")).setScale(2, HALF_UP);
            return buildDailyLocOrder(BigDecimal.valueOf(position.recurringAmount()), 1,
                    price, ticker, tradeDate, BUY);
        }
        return null;
    }

    // LOC 예산을 남은 거래일로 나눈 뒤 정수 주식 수로 주문 생성
    private List<Order> buildDailyLocOrder(BigDecimal totalBudget, int remainingTradingDays,
                                           BigDecimal price, Strategy.Ticker ticker,
                                           LocalDate tradeDate, Order.OrderDirection direction) {
        BigDecimal dailyBudget = totalBudget.divide(BigDecimal.valueOf(Math.max(remainingTradingDays, 1)), 2, HALF_UP);
        int quantity = dailyBudget.divide(price, 0, java.math.RoundingMode.DOWN).intValue();
        if (quantity <= 0) return List.of();
        return List.of(Order.planned(tradeDate, ticker, LOC, direction, quantity, price, AT_CLOSE,
                Order.leg("VR_" + direction, 1)));
    }

    // 매수 사다리 생성 — 최대 MAX_RUNGS단, 1주씩, poolLimit·pool 한도 내
    private List<Order> buildBuyOrders(VrPosition position, Strategy.Ticker ticker,
                                       BigDecimal currentPrice, LocalDate tradeDate) {
        // 가격 캡 = currentPrice × 1.10, null이면 캡 없음
        BigDecimal cap = currentPrice != null
                ? currentPrice.multiply(new BigDecimal("1.10")).setScale(2, HALF_UP)
                : null;

        // pool 사용 가능 잔여액 (poolLimit − poolUsed)
        BigDecimal poolBudget = position.poolLimit().subtract(position.poolUsed());

        List<Order> rawBuys = new ArrayList<>();
        BigDecimal cumBuyAmount = BigDecimal.ZERO;

        for (int m = 1; m <= MAX_RUNGS; m++) {
            // holdings=0, m=1 → divisor=0: division-by-zero 가드
            int divisor = position.holdings() + m - 1;
            if (divisor < 1) continue;

            // 기본 단가 = lowerBand / divisor
            BigDecimal price = position.buyPrice(m);

            // 가격 캡 적용 — currentPrice × 1.10 초과 시 캡 가격으로 교체
            if (cap != null && price.compareTo(cap) > 0) {
                price = cap;
            }

            // poolLimit 초과 시 이후 단 전량 제외 (누적 금액 > poolBudget)
            if (cumBuyAmount.add(price).compareTo(poolBudget) > 0) break;

            // 예수금 잔액 초과 시 이후 단 전량 제외 (누적 금액 > pool)
            if (cumBuyAmount.add(price).compareTo(position.pool()) > 0) break;

            rawBuys.add(Order.planned(tradeDate, ticker, LIMIT, BUY, 1, price, AT_OPEN));
            cumBuyAmount = cumBuyAmount.add(price);
        }

        // 캡 후 동일 가격의 연속 rung 수량 병합
        return mergeSamePriceOrders(rawBuys, ticker, tradeDate);
    }

    // 연속 동일 가격 BUY 주문 병합 — 1주×N → N주 1건
    private List<Order> mergeSamePriceOrders(List<Order> rawBuys, Strategy.Ticker ticker,
                                             LocalDate tradeDate) {
        if (rawBuys.isEmpty()) return List.of();

        List<Order> merged = new ArrayList<>();
        BigDecimal currentPrice = rawBuys.getFirst().price();
        int currentQty = 0;

        for (Order o : rawBuys) {
            if (o.price().compareTo(currentPrice) == 0) {
                // 같은 가격이면 수량 누적
                currentQty += o.quantity();
            } else {
                // 가격 전환 시 이전 그룹 확정
                merged.add(Order.planned(tradeDate, ticker, LIMIT, BUY, currentQty, currentPrice, AT_OPEN,
                        Order.leg("VR_BUY", merged.size() + 1)));
                currentPrice = o.price();
                currentQty = o.quantity();
            }
        }
        // 마지막 그룹 추가
        merged.add(Order.planned(tradeDate, ticker, LIMIT, BUY, currentQty, currentPrice, AT_OPEN,
                Order.leg("VR_BUY", merged.size() + 1)));
        return merged;
    }

    // 매도 사다리 생성 — 최대 MAX_RUNGS단, 1주씩 (holdings>MAX_RUNGS이면 마지막 단에 잔여 전량)
    private List<Order> buildSellOrders(VrPosition position, Strategy.Ticker ticker,
                                        LocalDate tradeDate) {
        // holdings=0이면 매도 없음
        if (position.holdings() == 0) return List.of();

        int maxS = Math.min(MAX_RUNGS, position.holdings());
        List<Order> sells = new ArrayList<>();

        for (int s = 1; s <= maxS; s++) {
            BigDecimal price = position.sellPrice(s);
            // holdings > MAX_RUNGS이면 마지막 단에 잔여 전량 — holdings - (MAX_RUNGS - 1)
            int quantity = (position.holdings() > MAX_RUNGS && s == MAX_RUNGS)
                    ? position.holdings() - (MAX_RUNGS - 1)
                    : 1;
            sells.add(Order.planned(tradeDate, ticker, LIMIT, SELL, quantity, price, AT_OPEN,
                    Order.leg("VR_SELL", s)));
        }
        return sells;
    }
}
