package com.kista.domain.strategy;

import com.kista.domain.model.order.Order;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.ReverseModePosition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.domain.model.order.Order.OrderDirection.BUY;
import static com.kista.domain.model.order.Order.OrderDirection.SELL;
import static com.kista.domain.model.order.Order.OrderType.LOC;
import static com.kista.domain.model.order.Order.OrderType.MOC;

// 리버스모드(소진 후) 전략 — 별지점 기준 분할 매도 + 쿼터 매수
@Slf4j
@Component
class ReverseInfiniteStrategy implements ReverseInfiniteTradingStrategy {

    // 첫날: MOC 매도만 (별지점 계산 없이 즉시 청산 시작)
    @Override
    public List<Order> buildFirstDayOrders(ReverseModePosition position, LocalDate tradeDate) {
        int mocSellQuantity = position.calcMocSellQuantity();
        if (mocSellQuantity < 1) {
            log.warn("[리버스모드 첫날] MOC 매도 수량 0 — holdings={}, divisionCount={}", position.holdings(), position.divisionCount());
            return List.of();
        }
        log.info("[리버스모드 첫날] MOC 매도 {}주", mocSellQuantity);
        return List.of(Order.planned(tradeDate, position.ticker(), MOC, SELL, mocSellQuantity, BigDecimal.ZERO));
    }

    // 두번째 날 이후: LOC 매도(별지점 위) + LOC 쿼터매수(별지점 아래)
    @Override
    public List<Order> buildOrders(ReverseModePosition position, LocalDate tradeDate) {
        List<Order> orders = new ArrayList<>();

        // LOC 매도 — 별지점 위에서 (starPointPrice 가격으로 LOC)
        int locSellQuantity = position.calcLocSellQuantity();
        if (locSellQuantity >= 1 && position.starPointPrice() != null) {
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, SELL, locSellQuantity, position.starPointPrice()));
            log.info("[리버스모드] LOC 매도 {}주 @ 별지점={}", locSellQuantity, position.starPointPrice());
        }

        // LOC 쿼터매수 — 별지점 아래에서 (starPointPrice - $0.01)
        int locBuyQuantity = position.calcLocBuyQuantity();
        if (locBuyQuantity >= 1 && position.starPointPrice() != null) {
            BigDecimal buyPrice = position.starPointPrice().subtract(InfinitePosition.TICK_SIZE);
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, BUY, locBuyQuantity, buyPrice));
            log.info("[리버스모드] LOC 쿼터매수 {}주 @ {}", locBuyQuantity, buyPrice);
        }

        return orders;
    }
}
