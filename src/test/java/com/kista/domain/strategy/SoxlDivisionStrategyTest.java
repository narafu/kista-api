package com.kista.domain.strategy;

import com.kista.domain.model.AccountBalance;
import com.kista.domain.model.TradingVariables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("SoxlDivisionStrategy 매매 변수 계산 검증")
class SoxlDivisionStrategyTest {

    private final SoxlDivisionStrategy strategy = new SoxlDivisionStrategy();

    @Test
    @DisplayName("Q=0: 잔고 없을 때 currentPrice를 A로 사용, currentRound=0, priceOffsetRate=0.2000")
    void case_q0() {
        // A=20, M=0, D=0, B=2000, K=100.00, T=0.0, priceOffsetRate=0.2000, targetPrice=24.00
        AccountBalance balance = new AccountBalance(0, null,
                new BigDecimal("0"),     // effectiveAmt: SOXL 없으므로 0
                new BigDecimal("2000")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("20"));

        assertThat(vars.averagePrice()).isEqualByComparingTo("20");
        assertThat(vars.quantity()).isEqualTo(0);
        assertThat(vars.purchaseAmount()).isEqualByComparingTo("0");
        assertThat(vars.evaluationAmount()).isEqualByComparingTo("0");
        assertThat(vars.totalAssets()).isEqualByComparingTo("2000");
        assertThat(vars.totalRounds()).isEqualTo(20);
        assertThat(vars.currentRound()).isEqualTo(0.0);
        assertThat(vars.unitAmount()).isEqualByComparingTo("100.00");
        assertThat(vars.targetProfitRate()).isEqualByComparingTo("0.20");
        assertThat(vars.priceOffsetRate()).isEqualByComparingTo("0.2000");
        assertThat(vars.targetPrice()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("Q=10: currentRound=1.33, priceOffsetRate=0.1733")
    void case_q10() {
        // A=20, M=200, D=210, B=3000, K=150.00, T=200/150=1.33
        // priceOffsetRate = 0.20*(1 - 2*1.33/20) = 0.1733
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20"),
                new BigDecimal("210"),   // effectiveAmt: 21×10 (SOXL 시가)
                new BigDecimal("2800")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("21"));

        assertThat(vars.averagePrice()).isEqualByComparingTo("20");
        assertThat(vars.quantity()).isEqualTo(10);
        assertThat(vars.purchaseAmount()).isEqualByComparingTo("200");
        assertThat(vars.evaluationAmount()).isEqualByComparingTo("210");
        assertThat(vars.totalAssets()).isEqualByComparingTo("3000");
        assertThat(vars.totalRounds()).isEqualTo(20);
        assertThat(vars.currentRound()).isCloseTo(1.33, within(0.01));
        assertThat(vars.unitAmount()).isEqualByComparingTo("150.00");
        assertThat(vars.targetProfitRate()).isEqualByComparingTo("0.20");
        assertThat(vars.priceOffsetRate()).isEqualByComparingTo("0.1734");
        assertThat(vars.targetPrice()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("Q=100: currentRound=5.0 (정확히 5회차), priceOffsetRate=0.1000")
    void case_q100() {
        // A=5, M=500, D=600, B=2000, K=100.00, T=500/100=5.0
        // priceOffsetRate = 0.20*(1 - 10/20) = 0.1000
        AccountBalance balance = new AccountBalance(100, new BigDecimal("5"),
                new BigDecimal("600"),   // effectiveAmt: 6×100 (SOXL 시가)
                new BigDecimal("1500")); // usdDeposit: 현금

        TradingVariables vars = strategy.calculate(balance, new BigDecimal("6"));

        assertThat(vars.averagePrice()).isEqualByComparingTo("5");
        assertThat(vars.quantity()).isEqualTo(100);
        assertThat(vars.purchaseAmount()).isEqualByComparingTo("500");
        assertThat(vars.evaluationAmount()).isEqualByComparingTo("600");
        assertThat(vars.totalAssets()).isEqualByComparingTo("2000");
        assertThat(vars.totalRounds()).isEqualTo(20);
        assertThat(vars.currentRound()).isEqualTo(5.0);
        assertThat(vars.unitAmount()).isEqualByComparingTo("100.00");
        assertThat(vars.targetProfitRate()).isEqualByComparingTo("0.20");
        assertThat(vars.priceOffsetRate()).isEqualByComparingTo("0.1000");
        assertThat(vars.targetPrice()).isEqualByComparingTo("6.00");
    }
}
