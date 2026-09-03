package com.kista.trading.domain.strategy;

import com.kista.trading.domain.model.Order;
import com.kista.trading.domain.model.InfinitePosition;
import com.kista.trading.domain.model.ReverseModePosition;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.kista.trading.domain.model.Order.OrderDirection.BUY;
import static com.kista.trading.domain.model.Order.OrderDirection.SELL;
import static com.kista.trading.domain.model.Order.OrderType.LOC;
import static com.kista.trading.domain.model.Order.OrderType.MOC;

// 리버스모드(소진 후) 전략 — 별지점 기준 분할 매도 + 쿼터 매수
@Slf4j
public class ReverseInfiniteStrategy {

    // 소진 직후 첫날: MOC 매도만 생성 (별지점 계산 없이 즉시 청산 시작)
    public List<Order> buildFirstDayOrders(ReverseModePosition position, LocalDate tradeDate) {
        int mocSellQuantity = position.calcMocSellQuantity();
        if (mocSellQuantity < 1) {
            log.warn("[리버스모드 첫날] MOC 매도 수량 0 — holdings={}, divisionCount={}", position.holdings(), position.divisionCount());
            return List.of();
        }
        log.info("[리버스모드 첫날] MOC 매도 {}주", mocSellQuantity);
        return List.of(Order.planned(tradeDate, position.ticker(), MOC, SELL, mocSellQuantity,
                BigDecimal.ZERO, "REVERSE_INFINITE_MOC_SELL"));
    }

    // 두번째 날 이후: LOC 매도(별지점 위) + LOC 쿼터매수(별지점 아래)
    // 쿼터매수 예산 소진 시(별지점은 있으나 1주도 못 사는 경우) 매수 대신 동일 수량 MOC 매도로 청산 가속
    public List<Order> buildOrders(ReverseModePosition position, LocalDate tradeDate) {
        if (position.isQuotaBuyExhausted()) {
            return buildQuotaExhaustedOrders(position, tradeDate);
        }

        List<Order> orders = new ArrayList<>();

        // LOC 매도 — 별지점 위에서 (starPointPrice 가격으로 LOC)
        int locSellQuantity = position.calcLocSellQuantity();
        if (locSellQuantity >= 1 && position.starPointPrice() != null) {
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, SELL, locSellQuantity,
                    position.starPointPrice(), "REVERSE_INFINITE_LOC_SELL"));
            log.info("[리버스모드] LOC 매도 {}주 @ 별지점={}", locSellQuantity, position.starPointPrice());
        }

        // LOC 쿼터매수 — 별지점 아래에서 (starPointPrice - $0.01)
        int locBuyQuantity = position.calcLocBuyQuantity();
        if (locBuyQuantity >= 1 && position.starPointPrice() != null) {
            BigDecimal buyPrice = position.starPointPrice().subtract(InfinitePosition.TICK_SIZE);
            orders.add(Order.planned(tradeDate, position.ticker(), LOC, BUY, locBuyQuantity,
                    buyPrice, "REVERSE_INFINITE_LOC_BUY"));
            log.info("[리버스모드] LOC 쿼터매수 {}주 @ {}", locBuyQuantity, buyPrice);
        }

        return orders;
    }

    // 쿼터매수 예산 소진 — 매수 생략, 동일 수량(quarter) MOC 매도로 청산 가속
    private List<Order> buildQuotaExhaustedOrders(ReverseModePosition position, LocalDate tradeDate) {
        int mocSellQuantity = position.calcLocSellQuantity();
        if (mocSellQuantity < 1) {
            log.warn("[리버스모드 예산소진] MOC 매도 수량 0 — holdings={}", position.holdings());
            return List.of();
        }
        log.info("[리버스모드 예산소진] 쿼터매수 불가 → MOC 매도 {}주", mocSellQuantity);
        return List.of(Order.planned(tradeDate, position.ticker(), MOC, SELL, mocSellQuantity,
                BigDecimal.ZERO, "REVERSE_INFINITE_QUOTA_MOC_SELL"));
    }
}
