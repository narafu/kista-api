package com.kista.domain.backtest;

import com.kista.domain.model.backtest.DailyCandle;
import com.kista.broker.domain.model.Direction;
import com.kista.broker.domain.model.Execution;
import com.kista.trading.domain.model.Order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// 체결 판정 SSOT — MockBrokerAdapter(모의계좌, 종가 기준)와 BacktestEngine(백테스트, OHLC 기준)이 공용
// domain/backtest는 ArchUnit @Component 허용 예외(domain/strategy)에 없으므로 Spring 빈 금지 — 순수 static 유틸
public final class FillSimulator {

    private FillSimulator() {
        // 인스턴스화 금지 — static 유틸리티
    }

    // 기존 MockBrokerAdapter.fills()와 완전히 동일한 시그니처·동작 — 모의계좌는 LIMIT도 종가 기준으로 판정한다(변경 금지)
    // MOC: 항상 체결 / LOC·LIMIT: 매수는 종가<=지정가, 매도는 종가>=지정가 (경계값 포함)
    public static boolean fills(Order order, BigDecimal closingPrice) {
        if (order.orderType() == Order.OrderType.MOC) return true;
        return order.direction() == Order.OrderDirection.BUY
                ? closingPrice.compareTo(order.price()) <= 0
                : closingPrice.compareTo(order.price()) >= 0;
    }

    // 백테스트 전용 OHLC 기반 체결 판정 — LIMIT은 고가/저가 터치로 판정(모의계좌의 종가 기준과 다름)
    // MOC/LOC는 candle.close() 기준으로 fills()와 동일 조건, LIMIT만 low/high로 재판정
    // externalOrderId는 백테스트엔 실물 주문번호가 없으므로 order.orderLeg()를 그대로 실어 보낸다
    public static List<Execution> simulate(List<Order> pendingOrders, DailyCandle candle) {
        List<Execution> executions = new ArrayList<>();
        for (Order order : pendingOrders) {
            if (!fillsOhlc(order, candle)) continue;
            BigDecimal fillPrice = order.orderType() == Order.OrderType.LIMIT ? order.price() : candle.close();
            executions.add(Execution.ofManualFill(candle.date(), order.ticker(), toDirection(order.direction()),
                    order.quantity(), fillPrice, order.orderLeg()));
        }
        return executions;
    }

    // trading Order.OrderDirection → broker Direction (값 1:1 대응, enum 이름 동일)
    private static Direction toDirection(Order.OrderDirection direction) {
        return switch (direction) {
            case BUY -> Direction.BUY;
            case SELL -> Direction.SELL;
        };
    }

    // 주문타입별 OHLC 체결 조건 — LIMIT만 저가/고가 터치, 나머지(MOC/LOC)는 종가 기준
    private static boolean fillsOhlc(Order order, DailyCandle candle) {
        if (order.orderType() != Order.OrderType.LIMIT) return fills(order, candle.close());
        return order.direction() == Order.OrderDirection.BUY
                ? candle.low().compareTo(order.price()) <= 0
                : candle.high().compareTo(order.price()) >= 0;
    }
}
