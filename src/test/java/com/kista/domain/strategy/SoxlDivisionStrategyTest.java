package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingVariables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SoxlDivisionStrategy 매매 변수 계산 검증")
class SoxlDivisionStrategyTest {

    private final SoxlDivisionStrategy strategy = new SoxlDivisionStrategy();

    @Test
    @DisplayName("Q=0: 잔고 없을 때 currentPrice를 A로 사용, T=0, S=0.2000")
    void case_q0() {
        // A=20, M=0, D=2000(예수금), B=2000, K=100.00, T=0, S=0.2000, P=24.00
        AccountBalance balance = new AccountBalance(0, null,
                new BigDecimal("0"),     // effectiveAmt: SOXL 없으므로 시가 평가 0
                new BigDecimal("2000")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("20"));

        assertThat(vars.a()).isEqualByComparingTo("20");
        assertThat(vars.q()).isEqualTo(0);
        assertThat(vars.m()).isEqualByComparingTo("0");
        assertThat(vars.d()).isEqualByComparingTo("2000");
        assertThat(vars.b()).isEqualByComparingTo("2000");
        assertThat(vars.k()).isEqualByComparingTo("100.00");
        assertThat(vars.t()).isEqualTo(0);
        assertThat(vars.s()).isEqualByComparingTo("0.2000");
        assertThat(vars.p()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("Q=10: T=1, S=0.1800")
    void case_q10_t1() {
        // A=20, M=200, D=2800(예수금), B=3000, K=150.00, T=floor(200/150)=1, S=0.1800, P=24.00
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20"),
                new BigDecimal("210"),   // effectiveAmt: 21 × 10 (SOXL 시가)
                new BigDecimal("2800")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("21"));

        assertThat(vars.a()).isEqualByComparingTo("20");
        assertThat(vars.q()).isEqualTo(10);
        assertThat(vars.m()).isEqualByComparingTo("200");
        assertThat(vars.d()).isEqualByComparingTo("2800");
        assertThat(vars.b()).isEqualByComparingTo("3000");
        assertThat(vars.k()).isEqualByComparingTo("150.00");
        assertThat(vars.t()).isEqualTo(1);
        assertThat(vars.s()).isEqualByComparingTo("0.1800");
        assertThat(vars.p()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("Q=100: T=5, S=0.1000")
    void case_q100_t5() {
        // A=5, M=500, D=1500(예수금), B=2000, K=100.00, T=floor(500/100)=5, S=0.1000, P=6.00
        AccountBalance balance = new AccountBalance(100, new BigDecimal("5"),
                new BigDecimal("600"),   // effectiveAmt: 6 × 100 (SOXL 시가)
                new BigDecimal("1500")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("6"));

        assertThat(vars.a()).isEqualByComparingTo("5");
        assertThat(vars.q()).isEqualTo(100);
        assertThat(vars.m()).isEqualByComparingTo("500");
        assertThat(vars.d()).isEqualByComparingTo("1500");
        assertThat(vars.b()).isEqualByComparingTo("2000");
        assertThat(vars.k()).isEqualByComparingTo("100.00");
        assertThat(vars.t()).isEqualTo(5);
        assertThat(vars.s()).isEqualByComparingTo("0.1000");
        assertThat(vars.p()).isEqualByComparingTo("6.00");
    }
}
