package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.DcaSkipReason;
import com.kista.shared.kernel.Market;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.DcaCondition;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaRuleDecision;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DcaRuleDecisionTest {

    private final DcaCycleOrderStrategy strategy = new DcaCycleOrderStrategy();

    private static BigDecimal bd(String v) { return v == null ? null : new BigDecimal(v); }

    private static DcaSizing amount(String v) { return new DcaSizing(DcaSizingType.AMOUNT, bd(v), null); }

    private static DcaSizing percent(String v) { return new DcaSizing(DcaSizingType.HOLDING_PERCENT, null, bd(v)); }

    private static DcaRule stock(OrderDirection dir, DcaSizing s, DcaShortfallPolicy p, List<DcaCondition> conds) {
        return new DcaRule("rk", dir, DcaFrequency.DAILY, null, null, OrderType.MOC, null, s, p, conds);
    }

    private static DcaRule crypto(OrderDirection dir, DcaSizing s, DcaShortfallPolicy p, List<DcaCondition> conds) {
        return new DcaRule("rk", dir, DcaFrequency.DAILY, null, null, OrderType.MARKET, 9, s, p, conds);
    }

    private static AccountBalance ledger(String holdings, String avg) { return new AccountBalance(bd(holdings), bd(avg), BigDecimal.ZERO); }

    private DcaRuleDecision decide(DcaRule r, Market m, AccountBalance ledger, String sellable, String close, String current, String cash) {
        return strategy.decideRule(new DcaCycleOrderStrategy.DcaRuleInputs(r, m, List.of(bd(close)), ledger, bd(sellable), null, bd(current), bd(cash)));
    }

    private DcaRuleDecision stockSell(DcaRule r, String ledgerQty, String sellable) {
        return decide(r, Market.US_STOCK, ledger(ledgerQty, "10"), sellable, "50", null, null);
    }

    private static DcaRuleDecision.Planned planned(DcaRuleDecision d) {
        assertThat(d).isInstanceOf(DcaRuleDecision.Planned.class);
        return (DcaRuleDecision.Planned) d;
    }

    private static DcaRuleDecision.Skipped skipped(DcaRuleDecision d) {
        assertThat(d).isInstanceOf(DcaRuleDecision.Skipped.class);
        return (DcaRuleDecision.Skipped) d;
    }

    @Test
    void 주식_보유비율_매도는_원장_보유의_비율_내림() {
        DcaRule r = stock(OrderDirection.SELL, percent("25"), DcaShortfallPolicy.PARTIAL, List.of());
        assertThat(planned(stockSell(r, "10", "10")).quantity()).isEqualByComparingTo("2");
    }

    @Test
    void 원장보다_판매가능이_적으면_PARTIAL은_판매가능만큼() {
        DcaRule r = stock(OrderDirection.SELL, amount("1000"), DcaShortfallPolicy.PARTIAL, List.of()); // 1000/50 = 20주
        assertThat(planned(stockSell(r, "20", "5")).quantity()).isEqualByComparingTo("5");
    }

    @Test
    void 원장보다_판매가능이_적으면_REJECT는_INSUFFICIENT_HOLDINGS() {
        DcaRule r = stock(OrderDirection.SELL, amount("1000"), DcaShortfallPolicy.REJECT, List.of());
        assertThat(skipped(stockSell(r, "20", "5")).reason()).isEqualTo(DcaSkipReason.INSUFFICIENT_HOLDINGS);
    }

    @Test
    void 가용_0이면_정책무관_NO_HOLDINGS() {
        for (DcaShortfallPolicy p : DcaShortfallPolicy.values()) {
            DcaRule r = stock(OrderDirection.SELL, amount("1000"), p, List.of());
            assertThat(skipped(stockSell(r, "20", "0")).reason()).isEqualTo(DcaSkipReason.NO_HOLDINGS);
        }
    }

    @Test
    void 판매가능수량_조회실패는_DATA_UNAVAILABLE() {
        DcaRule r = stock(OrderDirection.SELL, amount("1000"), DcaShortfallPolicy.PARTIAL, List.of());
        assertThat(skipped(stockSell(r, "20", null)).reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }

    @Test
    void 크립토_BUY_REJECT는_원화부족시_INSUFFICIENT_BALANCE() {
        DcaRule r = crypto(OrderDirection.BUY, amount("15000"), DcaShortfallPolicy.REJECT, List.of());
        DcaRuleDecision d = decide(r, Market.CRYPTO_KRW, ledger("0", null), null, "100000", "100000", "10000");
        assertThat(skipped(d).reason()).isEqualTo(DcaSkipReason.INSUFFICIENT_BALANCE);
    }

    @Test
    void 크립토_BUY_PARTIAL은_원화가용_수수료차감_금액으로() {
        DcaRule r = crypto(OrderDirection.BUY, amount("15000"), DcaShortfallPolicy.PARTIAL, List.of());
        DcaRuleDecision.Planned p = planned(decide(r, Market.CRYPTO_KRW, ledger("0", null), null, "100000", "100000", "10000"));
        assertThat(p.notional()).isEqualByComparingTo("9995");
    }

    @Test
    void 크립토_BUY_최소금액_미달은_BELOW_MIN_ORDER_AMOUNT() {
        DcaRule r = crypto(OrderDirection.BUY, amount("15000"), DcaShortfallPolicy.PARTIAL, List.of());
        DcaRuleDecision d = decide(r, Market.CRYPTO_KRW, ledger("0", null), null, "100000", "100000", "4000");
        assertThat(skipped(d).reason()).isEqualTo(DcaSkipReason.BELOW_MIN_ORDER_AMOUNT);
    }

    @Test
    void 크립토_보유비율_매도는_소수8자리_내림() {
        DcaRule r = crypto(OrderDirection.SELL, percent("50"), DcaShortfallPolicy.PARTIAL, List.of());
        DcaRuleDecision d = decide(r, Market.CRYPTO_KRW, ledger("0.00123457", "90000000"), "0.00123457", "100000000", "100000000", null);
        assertThat(planned(d).quantity()).isEqualByComparingTo("0.00061728");
    }

    @Test
    void AVG_PRICE_조건은_원장_평단으로_판정() { // 평단 100, 종가 99, LTE 0% → 통과
        DcaCondition c = new DcaCondition(DcaIndicator.AVG_PRICE, DcaOperator.LTE, null, BigDecimal.ZERO);
        DcaRule r = stock(OrderDirection.BUY, amount("1000"), DcaShortfallPolicy.PARTIAL, List.of(c));
        DcaRuleDecision d = decide(r, Market.US_STOCK, ledger("5", "100"), null, "99", null, null);
        assertThat(d).isInstanceOf(DcaRuleDecision.Planned.class);
        DcaRuleDecision d2 = decide(r, Market.US_STOCK, ledger("5", "90"), null, "99", null, null);
        assertThat(skipped(d2).reason()).isEqualTo(DcaSkipReason.CONDITION_NOT_MET);
    }

    @Test
    void 주식_BUY는_max_전일종가_현재가_캡가() {
        DcaRule r = stock(OrderDirection.BUY, amount("1000"), DcaShortfallPolicy.PARTIAL, List.of());
        DcaRuleDecision.Planned p = planned(decide(r, Market.US_STOCK, ledger("0", null), null, "50", "60", null));
        assertThat(p.referencePrice()).isEqualByComparingTo("60");
        assertThat(p.quantity()).isEqualByComparingTo("16"); // 1000/60
        assertThat(p.limitPrice()).isEqualByComparingTo(PriceCapPolicy.capFor(bd("60")));
        assertThat(p.ruleKey()).isEqualTo("rk");
    }

    @Test
    void 주식_BUY_1주_미만은_BELOW_ONE_SHARE() {
        DcaRule r = stock(OrderDirection.BUY, amount("10"), DcaShortfallPolicy.PARTIAL, List.of());
        assertThat(skipped(decide(r, Market.US_STOCK, ledger("0", null), null, "50", null, null)).reason())
                .isEqualTo(DcaSkipReason.BELOW_ONE_SHARE);
    }

    @Test
    void 크립토_SELL_기준가는_현재가() { // AMOUNT 10000 / 현재가 200000 = 0.05
        DcaRule r = crypto(OrderDirection.SELL, amount("10000"), DcaShortfallPolicy.PARTIAL, List.of());
        DcaRuleDecision.Planned p = planned(decide(r, Market.CRYPTO_KRW, ledger("1", "90000"), "1", "100000", "200000", null));
        assertThat(p.referencePrice()).isEqualByComparingTo("200000");
        assertThat(p.quantity()).isEqualByComparingTo("0.05");
    }

    @Test
    void SELL인데_원장이_null이면_DATA_UNAVAILABLE() {
        DcaRule r = stock(OrderDirection.SELL, amount("1000"), DcaShortfallPolicy.PARTIAL, List.of());
        assertThat(skipped(decide(r, Market.US_STOCK, null, "5", "50", null, null)).reason()).isEqualTo(DcaSkipReason.DATA_UNAVAILABLE);
    }
}
