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

    private static DcaRule rule(String key, OrderDirection dir, OrderType type, Integer hour, DcaSizing sizing) {
        return new DcaRule(key, dir, DcaFrequency.DAILY, null, null, type, hour, sizing, DcaShortfallPolicy.PARTIAL, List.of());
    }

    private static DcaSizing amount(String v) { return new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal(v), null); }

    private static DcaSizing percent(String v) { return new DcaSizing(DcaSizingType.HOLDING_PERCENT, null, new BigDecimal(v)); }

    @Test
    void 보유비율은_BUY_규칙에_올_수_없다() {
        assertThatIllegalArgumentException().isThrownBy(() -> rule("a", OrderDirection.BUY, OrderType.MOC, null, percent("10")));
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
    void MARKET인데_executionHour가_null이면_IAE() {
        assertThatIllegalArgumentException().isThrownBy(() -> rule("a", OrderDirection.BUY, OrderType.MARKET, null, amount("5000")));
    }

    @Test
    void orderLeg는_DCA_접두_ruleKey_이고_withRuleKey는_키만_교체() {
        DcaRule r = rule(null, OrderDirection.BUY, OrderType.MOC, null, amount("100")).withRuleKey("k1");
        assertThat(r.orderLeg()).isEqualTo("DCA_k1");
    }

    @Test
    void 규칙_11개는_IAE() {
        List<DcaRule> rules = IntStream.range(0, 11).mapToObj(i -> rule("k" + i, OrderDirection.BUY, OrderType.MOC, null, amount("100"))).toList();
        assertThatIllegalArgumentException().isThrownBy(() -> new DcaStrategyConfig(rules));
    }

    @Test
    void ruleKey_중복은_IAE_null은_허용() {
        DcaRule a = rule("k", OrderDirection.BUY, OrderType.MOC, null, amount("100"));
        assertThatIllegalArgumentException().isThrownBy(() -> new DcaStrategyConfig(List.of(a, a)));
        DcaRule n = rule(null, OrderDirection.BUY, OrderType.MOC, null, amount("100"));
        assertThat(new DcaStrategyConfig(List.of(n, n)).rules()).hasSize(2);
    }
}
