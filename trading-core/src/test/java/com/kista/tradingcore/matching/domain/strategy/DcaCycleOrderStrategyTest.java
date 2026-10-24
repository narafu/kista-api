package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderTiming;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.StrategyTicker;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaRuleDecision;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import com.kista.tradingcore.matching.domain.model.PlannedOrder;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

// 주식 규칙 판정(decideRule) — 조건 문구·skip 우선순위·기준가·수량 (구 단일 규칙 decide 테스트를 규칙 판정으로 이관)
class DcaCycleOrderStrategyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final String KEY = "11111111-1111-1111-1111-111111111111";
    private static final AccountBalance SELL_LEDGER = new AccountBalance(1000, new BigDecimal("50"), BigDecimal.ZERO); // SELL 기본 원장 — 판매가능이 상한
    private final DcaCycleOrderStrategy strategy = new DcaCycleOrderStrategy();

    private static DcaRule rule(OrderDirection dir, String amount, OrderType type, DcaCondition... conds) {
        return new DcaRule(KEY, dir, DcaFrequency.DAILY, null, null, type, null,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(amount), null), DcaShortfallPolicy.PARTIAL, List.of(conds));
    }

    private DcaRuleDecision decide(DcaRule r, List<BigDecimal> closes, Integer sellable, AccountBalance ledger,
                                   BigDecimal fearGreed, String currentPrice) {
        return strategy.decideRule(new DcaCycleOrderStrategy.DcaRuleInputs(r, Market.US_STOCK, closes, ledger,
                sellable == null ? null : BigDecimal.valueOf(sellable), fearGreed,
                currentPrice == null ? null : new BigDecimal(currentPrice), null, null));
    }

    private DcaRuleDecision decide(DcaRule r, String prevClose, Integer sellable) {
        return decide(r, prevClose == null ? null : List.of(new BigDecimal(prevClose)), sellable,
                r.direction() == OrderDirection.SELL ? SELL_LEDGER : null, null, null);
    }

    private DcaRuleDecision decideAt(DcaRule r, String prevClose, String currentPrice, Integer sellable) {
        return decide(r, List.of(new BigDecimal(prevClose)), sellable,
                r.direction() == OrderDirection.SELL ? SELL_LEDGER : null, null, currentPrice);
    }

    private static DcaRuleDecision.Skipped skipped(DcaSkipReason reason, String detail, String ref) {
        return new DcaRuleDecision.Skipped(KEY, reason, detail, ref == null ? null : new BigDecimal(ref));
    }

    private static DcaCondition rsi(DcaOperator op, String t) { return new DcaCondition(DcaIndicator.RSI, op, null, new BigDecimal(t)); }
    private static DcaCondition sma(DcaOperator op, int p) { return new DcaCondition(DcaIndicator.SMA, op, p, null); }
    private static DcaCondition avg(DcaOperator op, String n) { return new DcaCondition(DcaIndicator.AVG_PRICE, op, null, new BigDecimal(n)); }
    private static DcaCondition fearGreed(DcaOperator op, String t) { return new DcaCondition(DcaIndicator.FEAR_GREED, op, null, new BigDecimal(t)); }

    // n봉 완만한 상승(80.0부터 0.1씩) + 마지막 종가 — RSI 100, 마지막 종가 > SMA
    private static List<BigDecimal> rising(int n, String last) {
        List<BigDecimal> list = new java.util.ArrayList<>(IntStream.range(0, n - 1).mapToObj(i -> BigDecimal.valueOf(80 + i * 0.1)).toList());
        list.add(new BigDecimal(last));
        return list;
    }

    private static final List<BigDecimal> HUNDRED = List.of(new BigDecimal("100"));

    @Test
    void fear_greed_condition_met_plans_order() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, fearGreed(DcaOperator.LTE, "25")), HUNDRED, null, null,
                new BigDecimal("20.50"), null);
        assertThat(d).isInstanceOf(DcaRuleDecision.Planned.class);
    }

    @Test
    void fear_greed_condition_not_met_reports_negated_detail() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, fearGreed(DcaOperator.LTE, "25")), HUNDRED, null, null,
                new BigDecimal("45.09"), null);
        assertThat(d).isEqualTo(skipped(DcaSkipReason.CONDITION_NOT_MET, "공포탐욕지수 45.09 > 25", "100"));
    }

    @Test
    void fear_greed_equal_to_threshold_passes_lte() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, fearGreed(DcaOperator.LTE, "25")), HUNDRED, null, null,
                new BigDecimal("25.00"), null);
        assertThat(d).isInstanceOf(DcaRuleDecision.Planned.class);
    }

    @Test
    void fear_greed_null_is_data_unavailable() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, fearGreed(DcaOperator.GTE, "75")), HUNDRED, null, null, null, null);
        assertThat(d).isEqualTo(skipped(DcaSkipReason.DATA_UNAVAILABLE, "공포탐욕지수 조회 실패", "100"));
    }

    @Test
    void all_conditions_true_plans() {
        // 상승 시계열 → RSI 100, 종가 87.12 > SMA(20)
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, rsi(DcaOperator.GTE, "70"), sma(DcaOperator.GT, 20)),
                rising(30, "87.12"), null, null, null, null);
        assertThat(d).isInstanceOf(DcaRuleDecision.Planned.class);
    }

    @Test
    void first_false_condition_is_reported_with_negated_operator() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, rsi(DcaOperator.LTE, "35")), rising(30, "87.12"), null, null, null, null);
        assertThat(d).isEqualTo(skipped(DcaSkipReason.CONDITION_NOT_MET, "RSI(14) 100.00 > 35", "87.12"));
    }

    @Test
    void sma_detail_format() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, sma(DcaOperator.LT, 20)), rising(30, "87.12"), null, null, null, null);
        DcaRuleDecision.Skipped s = (DcaRuleDecision.Skipped) d;
        assertThat(s.reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
        assertThat(s.detail()).isEqualTo("전일 종가 87.12 ≥ SMA(20) 82.16");
    }

    @Test
    void insufficient_candles_win_over_false_condition() {
        // RSI 조건은 거짓(100 > 35)이지만 SMA(200)이 봉 부족 → DATA_UNAVAILABLE 우선
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, rsi(DcaOperator.LTE, "35"), sma(DcaOperator.LT, 200)),
                rising(30, "87.12"), null, null, null, null);
        assertThat(((DcaRuleDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void avg_price_condition_compares_rounded_threshold() {
        // 원장 평단 85 × (1 − 5%) = 80.75, 종가 90 > 80.75 → LTE 미충족
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")),
                null, new AccountBalance(10, new BigDecimal("85.00"), BigDecimal.ZERO), null, null);
        assertThat(d).isEqualTo(skipped(DcaSkipReason.CONDITION_NOT_MET, "전일 종가 90.00 > 평단 85.00 × (1 − 5%) = 80.75", "90.00"));
    }

    @Test
    void avg_price_positive_threshold_detail() {
        DcaRuleDecision d = decide(rule(OrderDirection.SELL, "300", OrderType.MOC, avg(DcaOperator.GTE, "10")), List.of(new BigDecimal("90.00")),
                5, new AccountBalance(10, new BigDecimal("85.00"), BigDecimal.ZERO), null, null);
        assertThat(((DcaRuleDecision.Skipped) d).detail()).isEqualTo("전일 종가 90.00 < 평단 85.00 × (1 + 10%) = 93.50");
    }

    @Test
    void avg_price_buy_with_zero_holdings_passes() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")),
                null, new AccountBalance(0, null, BigDecimal.ZERO), null, null);
        assertThat(d).isInstanceOf(DcaRuleDecision.Planned.class);
    }

    @Test
    void avg_price_sell_with_zero_holdings_reports_no_holdings() {
        DcaRuleDecision d = decide(rule(OrderDirection.SELL, "300", OrderType.MOC, avg(DcaOperator.GTE, "10")), List.of(new BigDecimal("90.00")),
                0, new AccountBalance(0, null, BigDecimal.ZERO), null, null);
        assertThat(((DcaRuleDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void avg_price_without_ledger_is_data_unavailable() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "300", OrderType.MOC, avg(DcaOperator.LTE, "-5")), List.of(new BigDecimal("90.00")),
                null, null, null, null);
        assertThat(((DcaRuleDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void condition_not_met_wins_over_no_holdings_and_below_one_share() {
        // SELL 판매가능 0 + 금액 < 종가지만 RSI 조건 거짓이 먼저
        DcaRuleDecision d = decide(rule(OrderDirection.SELL, "50", OrderType.MOC, rsi(DcaOperator.LTE, "35")), rising(30, "87.12"),
                0, SELL_LEDGER, null, null);
        assertThat(((DcaRuleDecision.Skipped) d).reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
    }

    @Test
    void buy_quantity_is_floor_of_amount_over_prev_close_with_cap_price() {
        DcaRuleDecision.Planned p = (DcaRuleDecision.Planned) decide(rule(OrderDirection.BUY, "300", OrderType.MOC), "87.12", null);
        assertThat(p.quantity()).isEqualByComparingTo("3");
        assertThat(p.limitPrice()).isEqualByComparingTo("91.48"); // 87.12 × 1.05 HALF_UP — PriceCapPolicy 결과와 일치해야 함
        assertThat(p.direction()).isEqualTo(OrderDirection.BUY);
        assertThat(p.ruleKey()).isEqualTo(KEY);
    }

    @Test
    void rule_timing_and_leg() {
        assertThat(rule(OrderDirection.BUY, "300", OrderType.MOO).timing()).isEqualTo(OrderTiming.AT_OPEN);
        assertThat(rule(OrderDirection.BUY, "300", OrderType.MOC).timing()).isEqualTo(OrderTiming.AT_CLOSE);
        assertThat(rule(OrderDirection.BUY, "300", OrderType.MOC).orderLeg()).isEqualTo("DCA_" + KEY);
    }

    @Test
    void below_one_share_skips() {
        DcaRuleDecision d = decide(rule(OrderDirection.BUY, "50", OrderType.MOC), "87.12", null);
        assertThat(d).isEqualTo(skipped(DcaSkipReason.BELOW_ONE_SHARE, "회차 금액 < 기준가 87.12", "87.12"));
    }

    @Test
    void buy_uses_current_price_when_above_prev_close() {
        DcaRuleDecision.Planned p = (DcaRuleDecision.Planned) decideAt(rule(OrderDirection.BUY, "1000", OrderType.MOO), "87.12", "95", null);
        assertThat(p.quantity()).isEqualByComparingTo("10");              // floor(1000 / 95), 전일 종가 기준이면 11
        assertThat(p.limitPrice()).isEqualByComparingTo("99.75");         // 95 × 1.05
        assertThat(p.referencePrice()).isEqualByComparingTo("95");
    }

    @Test
    void buy_keeps_prev_close_when_current_price_is_lower() {
        DcaRuleDecision.Planned p = (DcaRuleDecision.Planned) decideAt(rule(OrderDirection.BUY, "1000", OrderType.MOO), "87.12", "80", null);
        assertThat(p.quantity()).isEqualByComparingTo("11");
        assertThat(p.limitPrice()).isEqualByComparingTo("91.48");
        assertThat(p.referencePrice()).isEqualByComparingTo("87.12");
    }

    @Test
    void buy_falls_back_to_prev_close_when_current_price_missing_or_zero() {
        for (String current : new String[]{null, "0"}) {
            DcaRuleDecision.Planned p = (DcaRuleDecision.Planned) decideAt(rule(OrderDirection.BUY, "1000", OrderType.MOO), "87.12", current, null);
            assertThat(p.quantity()).isEqualByComparingTo("11");
            assertThat(p.limitPrice()).isEqualByComparingTo("91.48");
            assertThat(p.referencePrice()).isEqualByComparingTo("87.12");
        }
    }

    @Test
    void below_one_share_reports_current_price_basis() {
        DcaRuleDecision d = decideAt(rule(OrderDirection.BUY, "90", OrderType.MOO), "87.12", "95.0039", null);
        assertThat(d).isEqualTo(skipped(DcaSkipReason.BELOW_ONE_SHARE, "회차 금액 < 기준가 95.00", "95.0039"));
    }

    @Test
    void sell_ignores_current_price() {
        DcaRuleDecision.Planned p = (DcaRuleDecision.Planned) decideAt(rule(OrderDirection.SELL, "1000", OrderType.MOC), "100", "120", 50);
        assertThat(p.quantity()).isEqualByComparingTo("10");
        assertThat(p.limitPrice()).isEqualByComparingTo("100");
        assertThat(p.referencePrice()).isEqualByComparingTo("100");
    }

    @Test
    void missing_prev_close_is_data_unavailable() {
        assertThat(((DcaRuleDecision.Skipped) decide(rule(OrderDirection.BUY, "300", OrderType.MOC), null, null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void sell_partial_caps_to_sellable_and_uses_prev_close_price() {
        DcaRuleDecision.Planned p = (DcaRuleDecision.Planned) decide(rule(OrderDirection.SELL, "1000", OrderType.MOC), "100", 4);
        assertThat(p.quantity()).isEqualByComparingTo("4");
        assertThat(p.limitPrice()).isEqualByComparingTo("100");
    }

    @Test
    void sell_with_no_sellable_skips_no_holdings() {
        assertThat(((DcaRuleDecision.Skipped) decide(rule(OrderDirection.SELL, "1000", OrderType.MOC), "100", 0)).reason())
                .isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void sell_without_sellable_data_is_data_unavailable() {
        assertThat(((DcaRuleDecision.Skipped) decide(rule(OrderDirection.SELL, "1000", OrderType.MOC), "100", null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void sell_below_one_share_with_unknown_sellable_is_data_unavailable() {
        assertThat(((DcaRuleDecision.Skipped) decide(rule(OrderDirection.SELL, "50", OrderType.MOC), "87.12", null)).reason())
                .isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void sell_below_one_share_with_zero_sellable_is_no_holdings() {
        assertThat(((DcaRuleDecision.Skipped) decide(rule(OrderDirection.SELL, "50", OrderType.MOC), "87.12", 0)).reason())
                .isEqualTo(DcaSkipReason.NO_HOLDINGS);
    }

    @Test
    void policy_flags() {
        assertThat(strategy.endsCycleOnLiquidation()).isFalse();
        assertThat(strategy.needsCapCheck(null, null)).isFalse();
        assertThat(strategy.occupiesSeed()).isFalse();
    }
}
