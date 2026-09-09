package com.kista.matching.domain.model;

import com.kista.sharedkernel.StrategyTicker;

import java.math.BigDecimal;
import java.time.LocalDate;

// 커널이 산출하는 계획 주문 — 실행 생명주기 상태(id/status/externalOrderId/체결수량) 없음.
// trading이 Order.fromPlanned로 특정 계좌·사이클 PLANNED Order로 승격한다.
public record PlannedOrder(
        StrategyTicker ticker,      // 거래 종목
        LocalDate tradeDate,        // 거래일
        OrderType orderType,        // LOC/MOC/LIMIT
        OrderTiming timing,         // AT_OPEN/AT_CLOSE/IMMEDIATE
        OrderDirection direction,   // BUY/SELL
        String orderLeg,            // 전략 주문 다리 식별자
        Integer quantity,           // 주문 수량 (nullable — SELL "잔량 전부")
        BigDecimal price            // 주문 가격
) {
    public static final String UNKNOWN_LEG = "UNKNOWN";

    // blank leg는 UNKNOWN으로 정규화 — Order 컴팩트 생성자와 동일 규칙
    public PlannedOrder {
        if (orderLeg == null || orderLeg.isBlank()) orderLeg = UNKNOWN_LEG;
    }

    // 계산용 계획 주문 — AT_CLOSE 기본, leg 미지정
    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price) {
        return new PlannedOrder(ticker, tradeDate, orderType, OrderTiming.AT_CLOSE, direction,
                UNKNOWN_LEG, quantity, price);
    }

    // 접수 시점 명시 (INFINITE SELL → AT_OPEN)
    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price, OrderTiming timing) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, UNKNOWN_LEG, quantity, price);
    }

    // 전략 주문 다리 식별자 명시 — AT_CLOSE 기본
    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price, String orderLeg) {
        return new PlannedOrder(ticker, tradeDate, orderType, OrderTiming.AT_CLOSE, direction, orderLeg, quantity, price);
    }

    // 접수 시점 + 전략 주문 다리 식별자 명시
    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price,
                                  OrderTiming timing, String orderLeg) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, price);
    }

    // 전략 주문 다리 식별자 포맷 — "INFINITE_BUY" + 2 → "INFINITE_BUY_02" (Order.leg와 동일 규칙)
    public static String leg(String prefix, int index) {
        if (index < 1) throw new IllegalArgumentException("order leg index must be positive: " + index);
        return "%s_%02d".formatted(prefix, index);
    }

    // 가격 캡 보정 — 가격만 교체
    public PlannedOrder withPrice(BigDecimal newPrice) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, newPrice);
    }

    // 전략 주문 다리 식별자만 교체 — PrivacyStrategy의 순차 leg 부여(assignSequentialLegs)에 사용
    public PlannedOrder withLeg(String newLeg) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, newLeg, quantity, price);
    }

    // 수량만 교체 — 보유수량 캡 보정 재계산(PrivacyStrategy.capSellQuantitiesToHoldings)에 사용
    public PlannedOrder withQuantity(int newQuantity) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, newQuantity, price);
    }
}
