package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;

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
        BigDecimal price,           // 주문 가격
        boolean partialAllowed      // 예산 부족 시 수량 축소 허용 — false면 전량 아니면 제외(DCA REJECT 규칙), 그 외 true
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
                UNKNOWN_LEG, quantity, price, true);
    }

    // 접수 시점 명시 (INFINITE SELL → AT_OPEN)
    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price, OrderTiming timing) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, UNKNOWN_LEG, quantity, price, true);
    }

    // 전략 주문 다리 식별자 명시 — AT_CLOSE 기본
    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price, String orderLeg) {
        return new PlannedOrder(ticker, tradeDate, orderType, OrderTiming.AT_CLOSE, direction, orderLeg, quantity, price, true);
    }

    // 접수 시점 + 전략 주문 다리 식별자 명시
    public static PlannedOrder of(LocalDate tradeDate, StrategyTicker ticker, OrderType orderType,
                                  OrderDirection direction, int quantity, BigDecimal price,
                                  OrderTiming timing, String orderLeg) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, price, true);
    }

    // 전략 주문 다리 식별자 포맷 — "INFINITE_BUY" + 2 → "INFINITE_BUY_02" (Order.leg와 동일 규칙)
    public static String leg(String prefix, int index) {
        if (index < 1) throw new IllegalArgumentException("order leg index must be positive: " + index);
        return "%s_%02d".formatted(prefix, index);
    }

    // 가격 캡 보정 — 가격만 교체
    public PlannedOrder withPrice(BigDecimal newPrice) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, newPrice, partialAllowed);
    }

    // 전략 주문 다리 식별자만 교체 — PrivacyStrategy의 순차 leg 부여(assignSequentialLegs)에 사용
    public PlannedOrder withLeg(String newLeg) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, newLeg, quantity, price, partialAllowed);
    }

    // 수량만 교체 — 보유수량 캡 보정 재계산(PrivacyStrategy.capSellQuantitiesToHoldings)에 사용
    public PlannedOrder withQuantity(int newQuantity) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, newQuantity, price, partialAllowed);
    }

    // 수량 축소 허용 여부만 교체 — DCA REJECT 규칙 주문은 false
    public PlannedOrder withPartialAllowed(boolean newPartialAllowed) {
        return new PlannedOrder(ticker, tradeDate, orderType, timing, direction, orderLeg, quantity, price, newPartialAllowed);
    }
}
