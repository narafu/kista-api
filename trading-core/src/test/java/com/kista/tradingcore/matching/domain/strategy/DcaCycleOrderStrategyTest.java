package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaConfig;
import com.kista.tradingcore.matching.domain.model.DcaDecision;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DcaCycleOrderStrategyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private final DcaCycleOrderStrategy strategy = new DcaCycleOrderStrategy();

    private static DcaConfig config(OrderDirection dir, String amount, OrderType type) {
        return new DcaConfig(dir, new BigDecimal(amount), type, DcaFrequency.DAILY, null, null);
    }

    private DcaDecision decide(DcaConfig c, String prevClose, Integer sellable) {
        return decide(c, prevClose == null ? null : List.of(new BigDecimal(prevClose)), sellable, null);
    }

    private DcaDecision decide(DcaConfig c, List<BigDecimal> closes, Integer sellable, DcaCycleOrderStrategy.DcaInputs.Holding holding) {
        return strategy.decide(new DcaCycleOrderStrategy.DcaInputs(c, StrategyTicker.UPRO, DAY, closes, sellable, holding));
    }

    private static DcaConfig withConditions(OrderDirection dir, DcaCondition... conds) {
        return new DcaConfig(dir, new BigDecimal("300"), OrderType.MOC, DcaFrequency.DAILY, null, null, List.of(conds));
    }

    private static DcaCondition rsi(DcaOperator op, String t) { return new DcaCondition(DcaIndicator.RSI, op, null, new BigDecimal(t)); }
    private static DcaCondition sma(DcaOperator op, int p) { return new DcaCondition(DcaIndicator.SMA, op, p, null); }
    private static DcaCondition avg(DcaOperator op, String n) { return new DcaCondition(DcaIndicator.AVG_PRICE, op, null, new BigDecimal(n)); }

    // n봉 완만한 상승(80.0부터 0.1씩) + 마지막 종가 — RSI 100, 마지막 종가 > SMA
    private static List<BigDecimal> rising(int n, String last) {
        List<BigDecimal> list = new java.util.ArrayList<>(IntStream.range(0, n - 1).mapToObj(i -> BigDecimal.valueOf(80 + i * 0.1)).toList());
        list.add(new BigDecimal(last));
        return list;
    }

    @Test
    void all_conditions_true_plans() {
        // 상승 시계열 → RSI 100, 종가 87.12 > SMA(20)
        DcaDecision d = decide(withConditions(OrderDirection.BUY, rsi(DcaOperator.GTE, "70"), sma(DcaOperator.GT, 20)),
                rising(30, "87.12"), null, null);
        assertThat(d).isInstanceOf(DcaDecision.Planned.class);
    }

    @Test
    void first_false_condition_is_reported_with_negated_operator() {
        DcaDecision d = decide(withConditions(OrderDirection.BUY, rsi(DcaOperator.LTE, "35")), rising(30, "87.12"), null, null);
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.CONDITION_NOT_MET, "RSI(14) 100.00 > 35", new BigDecimal("87.12")));
    }

    @Test
    void sma_detail_format() {
        DcaDecision d = decide(withConditions(OrderDirection.BUY, sma(DcaOperator.LT, 20)), rising(30, "87.12"), null, null);
        DcaDecision.Skipped s = (DcaDecision.Skipped) d;
        assertThat(s.reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
        assertThat(s.detail()).startsWith("전일 종가 87.12 ≥ SMA(20) ");
    }

    @Test
    void insufficient_candles_win_over_false_condition() {
        // RSI 조건은 거짓(100 > 35)이지만 SMA(200)이 봉 부족 → DATA_UNAVAILABLE 우선
        DcaDecision d = decide(withConditions(OrderDirection.BUY, rsi(DcaOperator.LTE, "35"), sma(DcaOperator.LT, 200)),
                rising(30, "87.12"), null, null);
        assertThat(((DcaDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void avg_price_condition_compares_rounded_threshold() {
        // 평단 85 × (1 − 5%) = 80.75, 종가 90 > 80.75 → LTE 미충족
        DcaDecision d = decide(withConditions(OrderDirection.BUY, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")), null,
                new DcaCycleOrderStrategy.DcaInputs.Holding(10, new BigDecimal("85.00")));
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.CONDITION_NOT_MET,
                "전일 종가 90.00 > 평단 85.00 × (1 − 5%) = 80.75", new BigDecimal("90.00")));
    }

    @Test
    void avg_price_positive_threshold_detail() {
        DcaDecision d = decide(withConditions(OrderDirection.SELL, avg(DcaOperator.GTE, "10")), List.of(new BigDecimal("90.00")), 5,
                new DcaCycleOrderStrategy.DcaInputs.Holding(10, new BigDecimal("85.00")));
        assertThat(((DcaDecision.Skipped) d).detail()).isEqualTo("전일 종가 90.00 < 평단 85.00 × (1 + 10%) = 93.50");
    }

    @Test
    void avg_price_buy_with_zero_holdings_passes() {
        DcaDecision d = decide(withConditions(OrderDirection.BUY, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")), null,
                new DcaCycleOrderStrategy.DcaInputs.Holding(0, null));
        assertThat(d).isInstanceOf(DcaDecision.Planned.class);
    }

    @Test
    void avg_price_sell_with_zero_holdings_reports_no_holdings() {
        DcaDecision d = decide(withConditions(OrderDirection.SELL, avg(DcaOperator.GTE, "10")), List.of(new BigDecimal("90.00")), 0,
                new DcaCycleOrderStrategy.DcaInputs.Holding(0, null));
        assertThat(((DcaDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void avg_price_without_holding_data_is_data_unavailable() {
        DcaDecision d = decide(withConditions(OrderDirection.BUY, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")), null, null);
        assertThat(((DcaDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void condition_not_met_wins_over_no_holdings_and_below_one_share() {
        // SELL 판매가능 0 + 금액 < 종가지만 RSI 조건 거짓이 먼저
        DcaConfig c = new DcaConfig(OrderDirection.SELL, new BigDecimal("50"), OrderType.MOC, DcaFrequency.DAILY, null, null,
                List.of(rsi(DcaOperator.LTE, "35")));
        DcaDecision d = decide(c, rising(30, "87.12"), 0, null);
        assertThat(((DcaDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
    }

    @Test
    void buy_quantity_is_floor_of_amount_over_prev_close_with_cap_price() {
        DcaDecision d = decide(config(OrderDirection.BUY, "300", OrderType.MOC), "87.12", null);
        PlannedOrder o = ((DcaDecision.Planned) d).order();
        assertThat(o.quantity()).isEqualTo(3);
        assertThat(o.price()).isEqualByComparingTo("91.48"); // 87.12 × 1.05 HALF_UP — PriceCapPolicy 결과와 일치해야 함
        assertThat(o.orderType()).isEqualTo(OrderType.MOC);
        assertThat(o.timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(o.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(o.orderLeg()).isEqualTo("DCA_BUY");
    }

    @Test
    void moo_is_at_open() {
        PlannedOrder o = ((DcaDecision.Planned) decide(config(OrderDirection.BUY, "300", OrderType.MOO), "87.12", null)).order();
        assertThat(o.timing()).isEqualTo(OrderTiming.AT_OPEN);
    }

    @Test
    void below_one_share_skips() {
        DcaDecision d = decide(config(OrderDirection.BUY, "50", OrderType.MOC), "87.12", null);
        assertThat(d).isEqualTo(new DcaDecision.Skipped(DcaSkipReason.BELOW_ONE_SHARE, "회차 금액 50 < 전일 종가 87.12", new BigDecimal("87.12")));
    }

    @Test
    void missing_prev_close_is_data_unavailable() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.BUY, "300", OrderType.MOC), null, null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void sell_caps_to_sellable_and_uses_prev_close_price() {
        PlannedOrder o = ((DcaDecision.Planned) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", 4)).order();
        assertThat(o.quantity()).isEqualTo(4);
        assertThat(o.price()).isEqualByComparingTo("100");
        assertThat(o.orderLeg()).isEqualTo("DCA_SELL");
    }

    @Test
    void sell_with_no_sellable_skips_no_holdings() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", 0)).reason())
                .isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void sell_without_sellable_data_is_data_unavailable() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.SELL, "1000", OrderType.MOC), "100", null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void sell_below_one_share_with_unknown_sellable_is_data_unavailable() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.SELL, "50", OrderType.MOC), "87.12", null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void sell_below_one_share_with_zero_sellable_is_no_holdings() {
        assertThat(((DcaDecision.Skipped) decide(config(OrderDirection.SELL, "50", OrderType.MOC), "87.12", 0)).reason())
                .isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void fitBuysToBudget_shrinks_by_cap_price() {
        PlannedOrder buy = PlannedOrder.of(DAY, StrategyTicker.UPRO, OrderType.MOC, OrderDirection.BUY, 20,
                new BigDecimal("105"), OrderTiming.AT_CLOSE, "DCA_BUY");
        List<PlannedOrder> fitted = strategy.fitBuysToBudget(List.of(buy), new BigDecimal("1000"));
        assertThat(fitted).singleElement().extracting(PlannedOrder::quantity).isEqualTo(9);
    }

    @Test
    void fitBuysToBudget_returns_empty_below_one_share() {
        PlannedOrder buy = PlannedOrder.of(DAY, StrategyTicker.UPRO, OrderType.MOC, OrderDirection.BUY, 3,
                new BigDecimal("105"), OrderTiming.AT_CLOSE, "DCA_BUY");
        assertThat(strategy.fitBuysToBudget(List.of(buy), new BigDecimal("100"))).isEmpty();
    }

    @Test
    void policy_flags() {
        assertThat(strategy.endsCycleOnLiquidation()).isFalse();
        assertThat(strategy.needsCapCheck(null, null)).isFalse();
        assertThat(strategy.allocationPriority()).isEqualTo(200);
        assertThat(strategy.snapshotsBrokerBalance()).isTrue();
    }
}
