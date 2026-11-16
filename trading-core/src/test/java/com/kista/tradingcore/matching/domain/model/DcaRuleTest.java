package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class DcaRuleTest {

    private static DcaRule rule(String key, OrderDirection dir, OrderType type, DcaSizing sizing) {
        return new DcaRule(key, dir, DcaFrequency.DAILY, null, null, type, sizing, DcaShortfallPolicy.PARTIAL, List.of());
    }

    private static DcaSizing amount(String v) { return new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(v), null); }

    private static DcaSizing percent(String v) { return new DcaSizing(DcaSizingType.HOLDING_PERCENT, null, new BigDecimal(v)); }

    @Test
    void 보유비율은_BUY_규칙에_올_수_없다() {
        assertThatIllegalArgumentException().isThrownBy(() -> rule("a", OrderDirection.BUY, OrderType.MOC, percent("10")));
    }

    @Test
    void 보유비율은_0초과_100이하() {
        assertThatIllegalArgumentException().isThrownBy(() -> percent("0"));
        assertThatIllegalArgumentException().isThrownBy(() -> percent("101"));
        assertThat(percent("100").percent()).isEqualByComparingTo("100");
    }

    @Test
    void 규모는_타입별로_배타() {
        assertThatIllegalArgumentException().isThrownBy(() -> new DcaSizing(DcaSizingType.AMOUNT, BigDecimal.TEN, BigDecimal.ONE));
        assertThatIllegalArgumentException().isThrownBy(() -> new DcaSizing(DcaSizingType.HOLDING_PERCENT, BigDecimal.TEN, BigDecimal.ONE));
    }

    @Test
    void MOO_MOC_외_주문유형은_IAE() {
        assertThatIllegalArgumentException().isThrownBy(() -> rule("a", OrderDirection.BUY, OrderType.LOC, amount("5000")));
    }

    @Test
    void orderLeg는_DCA_접두_ruleKey_이고_withRuleKey는_키만_교체() {
        DcaRule r = rule(null, OrderDirection.BUY, OrderType.MOC, amount("100")).withRuleKey("k1");
        assertThat(r.orderLeg()).isEqualTo("DCA_k1");
    }

    @Test
    void 규칙_11개는_IAE() {
        List<DcaRule> rules = IntStream.range(0, 11).mapToObj(i -> rule("k" + i, OrderDirection.BUY, OrderType.MOC, amount("100"))).toList();
        assertThatIllegalArgumentException().isThrownBy(() -> new DcaStrategyConfig(rules));
    }

    @Test
    void ruleKey_중복은_IAE_null은_허용() {
        DcaRule a = rule("k", OrderDirection.BUY, OrderType.MOC, amount("100"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DcaStrategyConfig(List.of(a, a)));
        DcaRule n = rule(null, OrderDirection.BUY, OrderType.MOC, amount("100"));
        assertThat(new DcaStrategyConfig(List.of(n, n)).rules()).hasSize(2);
    }

    @Test
    void 주식_매주_규칙은_주말_요일을_거부한다() {
        for (java.time.DayOfWeek weekend : List.of(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)) {
            assertThatIllegalArgumentException().as(weekend.name()).isThrownBy(() -> new DcaRule("a", OrderDirection.BUY, DcaFrequency.WEEKLY,
                            weekend, null, OrderType.MOC, amount("100"), DcaShortfallPolicy.PARTIAL, List.of()))
                    .withMessage("주말 요일은 지정할 수 없습니다.");
        }
    }

    @Test
    void leg_접두사_왕복과_비DCA_leg_판별() {
        DcaRule r = rule("weekly-1", OrderDirection.BUY, OrderType.MOC, amount("100"));
        assertThat(DcaRule.isDcaLeg(r.orderLeg())).isTrue();
        assertThat(DcaRule.ruleKeyOf(r.orderLeg())).isEqualTo("weekly-1");
        assertThat(DcaRule.isDcaLeg(PlannedOrder.UNKNOWN_LEG)).isFalse();
        assertThat(DcaRule.isDcaLeg("INFINITE_BUY_01")).isFalse();
    }
}
