package com.kista.tradingcore.stats.domain.backtest;

import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.stats.domain.model.DailyCandle;
import com.kista.tradingcore.broker.domain.model.Execution;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

// 체결 판정 SSOT — 백테스트(BacktestEngine)가 사용, MockBrokerAdapter(모의계좌)는 동일 OHLC 규칙을 broker 타입으로 복제
// domain 레이어는 Spring 빈 금지(HexagonalArchitectureTest) — 순수 static 유틸
public final class FillSimulator {

    private FillSimulator() {
        // 인스턴스화 금지 — static 유틸리티
    }

    // 종가 기준 판정 — MOC: 항상 체결 / LOC·LIMIT: 매수는 종가<=지정가, 매도는 종가>=지정가 (경계값 포함)
    // LIMIT의 실제 판정은 fillsOhlc가 저가/고가로 재판정한다
    public static boolean fills(PlannedOrder order, BigDecimal closingPrice) {
        if (order.orderType() == OrderType.MOC || order.orderType() == OrderType.MOO) return true;
        return order.direction() == OrderDirection.BUY
                ? closingPrice.compareTo(order.price()) <= 0
                : closingPrice.compareTo(order.price()) >= 0;
    }

    // 백테스트 전용 OHLC 기반 체결 판정 — LIMIT은 고가/저가 터치로 판정(종가 기준 fills와 다름)
    // MOC/LOC는 candle.close() 기준으로 fills()와 동일 조건, LIMIT만 low/high로 재판정
    // externalOrderId는 백테스트엔 실물 주문번호가 없으므로 order.orderLeg()를 그대로 실어 보낸다
    public static List<Execution> simulate(List<PlannedOrder> pendingOrders, DailyCandle candle) {
        List<Execution> executions = new ArrayList<>();
        for (PlannedOrder order : pendingOrders) {
            if (!fillsOhlc(order, candle)) continue;
            BigDecimal fillPrice = order.orderType() == OrderType.LIMIT ? limitFillPrice(order, candle) : candle.close();
            executions.add(Execution.ofManualFill(candle.date(), order.ticker(), order.direction(),
                    order.quantity(), fillPrice, order.orderLeg()));
        }
        return executions;
    }

    // LIMIT 체결가 — 시가가 지정가보다 유리하게 열리면(매수: 시가<지정가, 매도: 시가>지정가) 개장 즉시 시가에 체결된다
    private static BigDecimal limitFillPrice(PlannedOrder order, DailyCandle candle) {
        return order.direction() == OrderDirection.BUY
                ? order.price().min(candle.open())
                : order.price().max(candle.open());
    }

    // 주문타입별 OHLC 체결 조건 — LIMIT만 저가/고가 터치, 나머지(MOC/LOC)는 종가 기준
    private static boolean fillsOhlc(PlannedOrder order, DailyCandle candle) {
        if (order.orderType() != OrderType.LIMIT) return fills(order, candle.close());
        return order.direction() == OrderDirection.BUY
                ? candle.low().compareTo(order.price()) <= 0
                : candle.high().compareTo(order.price()) >= 0;
    }
}
