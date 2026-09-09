package com.kista.stats.domain.backtest;

import com.kista.stats.domain.model.backtest.DailyCandle;
import com.kista.broker.domain.model.Execution;
import com.kista.matching.domain.model.PlannedOrder;
import com.kista.sharedkernel.OrderType;
import com.kista.sharedkernel.OrderDirection;
import com.kista.sharedkernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 체결 판정 SSOT 검증 — fills()(모의계좌, 종가 기준)와 simulate()(백테스트, OHLC 기준) 양쪽 커버
class FillSimulatorTest {

    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 7, 25);

    private static PlannedOrder order(OrderType orderType, OrderDirection direction, BigDecimal price) {
        return PlannedOrder.of(TRADE_DATE, StrategyTicker.TQQQ, orderType, direction, 5, price);
    }

    // --- fills() 기존 동작 회귀 ---

    @Test
    @DisplayName("fills() — MOC는 지정가와 무관하게 항상 체결된다")
    void fillsMocAlwaysTrue() {
        PlannedOrder moc = order(OrderType.MOC, OrderDirection.BUY, new BigDecimal("100.00"));

        assertThat(FillSimulator.fills(moc, new BigDecimal("999.99"))).isTrue();
    }

    @Test
    @DisplayName("fills() — LOC 매수는 종가==지정가 경계값도 체결이다")
    void fillsLocBuyBoundaryEqual() {
        PlannedOrder loc = order(OrderType.LOC, OrderDirection.BUY, new BigDecimal("100.00"));

        assertThat(FillSimulator.fills(loc, new BigDecimal("100.00"))).isTrue();
    }

    @Test
    @DisplayName("fills() — LOC 매도는 종가==지정가 경계값도 체결이다")
    void fillsLocSellBoundaryEqual() {
        PlannedOrder loc = order(OrderType.LOC, OrderDirection.SELL, new BigDecimal("100.00"));

        assertThat(FillSimulator.fills(loc, new BigDecimal("100.00"))).isTrue();
    }

    @Test
    @DisplayName("fills() — LIMIT도 (모의계좌 계약대로) 종가 기준으로 판정한다 — OHLC 아님")
    void fillsLimitStillUsesClosingPriceNotOhlc() {
        // 종가가 지정가를 초과하면 저가와 무관하게 미체결이어야 한다 — 모의계좌 계약 유지 확인
        PlannedOrder limit = order(OrderType.LIMIT, OrderDirection.BUY, new BigDecimal("100.00"));

        assertThat(FillSimulator.fills(limit, new BigDecimal("110.00"))).isFalse();
        assertThat(FillSimulator.fills(limit, new BigDecimal("100.00"))).isTrue();
    }

    // --- simulate() 신규 OHLC 분기 ---

    @Test
    @DisplayName("simulate() — LIMIT 매수는 저가<=지정가면 체결(체결가=지정가), 저가>지정가면 미체결")
    void simulateLimitBuyTouchesLow() {
        PlannedOrder fillable = order(OrderType.LIMIT, OrderDirection.BUY, new BigDecimal("100.00"));
        DailyCandle touchesLow = new DailyCandle(TRADE_DATE, new BigDecimal("105.00"), new BigDecimal("106.00"),
                new BigDecimal("100.00"), new BigDecimal("104.00")); // low==price 경계 — 체결

        List<Execution> executions = FillSimulator.simulate(List.of(fillable), touchesLow);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("100.00"); // 체결가는 종가 아닌 지정가

        PlannedOrder unfillable = order(OrderType.LIMIT, OrderDirection.BUY, new BigDecimal("100.00"));
        DailyCandle missesLow = new DailyCandle(TRADE_DATE, new BigDecimal("105.00"), new BigDecimal("106.00"),
                new BigDecimal("100.01"), new BigDecimal("104.00")); // low>price — 미체결

        assertThat(FillSimulator.simulate(List.of(unfillable), missesLow)).isEmpty();
    }

    @Test
    @DisplayName("simulate() — LIMIT 매도는 고가>=지정가면 체결(체결가=지정가), 고가<지정가면 미체결")
    void simulateLimitSellTouchesHigh() {
        PlannedOrder fillable = order(OrderType.LIMIT, OrderDirection.SELL, new BigDecimal("100.00"));
        DailyCandle touchesHigh = new DailyCandle(TRADE_DATE, new BigDecimal("95.00"), new BigDecimal("100.00"),
                new BigDecimal("94.00"), new BigDecimal("96.00")); // high==price 경계 — 체결

        List<Execution> executions = FillSimulator.simulate(List.of(fillable), touchesHigh);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("100.00");

        PlannedOrder unfillable = order(OrderType.LIMIT, OrderDirection.SELL, new BigDecimal("100.00"));
        DailyCandle missesHigh = new DailyCandle(TRADE_DATE, new BigDecimal("95.00"), new BigDecimal("99.99"),
                new BigDecimal("94.00"), new BigDecimal("96.00")); // high<price — 미체결

        assertThat(FillSimulator.simulate(List.of(unfillable), missesHigh)).isEmpty();
    }

    @Test
    @DisplayName("simulate() — LOC/MOC는 OHLC 메서드에서도 종가 기준이며 high/low는 무시한다")
    void simulateLocMocIgnoreHighLow() {
        // LOC 매수: low는 지정가를 훨씬 밑돌지만 종가가 지정가를 초과하면 미체결이어야 한다
        PlannedOrder loc = order(OrderType.LOC, OrderDirection.BUY, new BigDecimal("100.00"));
        DailyCandle candle = new DailyCandle(TRADE_DATE, new BigDecimal("102.00"), new BigDecimal("103.00"),
                new BigDecimal("50.00"), new BigDecimal("101.00")); // low=50(지정가 밑) 이지만 close=101(지정가 초과)

        assertThat(FillSimulator.simulate(List.of(loc), candle)).isEmpty();

        // MOC는 OHLC 무관 항상 체결, 체결가는 종가
        PlannedOrder moc = order(OrderType.MOC, OrderDirection.SELL, new BigDecimal("999.00"));
        List<Execution> executions = FillSimulator.simulate(List.of(moc), candle);

        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).price()).isEqualByComparingTo("101.00"); // 종가
    }

    @Test
    @DisplayName("simulate() — externalOrderId 자리에 orderLeg를 실어 보낸다")
    void simulateUsesOrderLegAsExternalOrderId() {
        PlannedOrder withLeg = order(OrderType.MOC, OrderDirection.BUY, new BigDecimal("100.00"))
                .withLeg("BUY_LADDER_1");
        DailyCandle candle = new DailyCandle(TRADE_DATE, new BigDecimal("100.00"), new BigDecimal("101.00"),
                new BigDecimal("99.00"), new BigDecimal("100.50"));

        List<Execution> executions = FillSimulator.simulate(List.of(withLeg), candle);

        assertThat(executions.get(0).externalOrderId()).isEqualTo("BUY_LADDER_1");
    }
}
