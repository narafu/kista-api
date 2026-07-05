package com.kista.domain.model;

import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.StrategyVrDetail;
import com.kista.domain.model.strategy.VrPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("VrPosition 도메인 계산 검증")
class VrPositionTest {

    // 기본 VrPosition — holdings=10, pool=5000, V=10000, bandWidth=15.00
    private VrPosition position(int holdings, BigDecimal pool, BigDecimal value, BigDecimal bandWidth) {
        AccountBalance balance = new AccountBalance(holdings, holdings > 0 ? new BigDecimal("50") : null, pool);
        return new VrPosition(
                balance,
                value,
                bandWidth,
                new BigDecimal("3750"), // poolLimit (임의)
                BigDecimal.ZERO          // poolUsed
        );
    }

    // ── 밴드 계산 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("lowerBand = V × (1 − bandWidth/100), scale=2 HALF_UP")
    void lowerBand_calculatedCorrectly() {
        // V=10000, bandWidth=15.00 → lowerBand = 10000 × 0.85 = 8500.00
        VrPosition pos = position(5, new BigDecimal("5000"), new BigDecimal("10000"), new BigDecimal("15.00"));
        assertThat(pos.lowerBand()).isEqualByComparingTo("8500.00");
    }

    @Test
    @DisplayName("upperBand = V × (1 + bandWidth/100), scale=2 HALF_UP")
    void upperBand_calculatedCorrectly() {
        // V=10000, bandWidth=15.00 → upperBand = 10000 × 1.15 = 11500.00
        VrPosition pos = position(5, new BigDecimal("5000"), new BigDecimal("10000"), new BigDecimal("15.00"));
        assertThat(pos.upperBand()).isEqualByComparingTo("11500.00");
    }

    @Test
    @DisplayName("밴드 소수점 반올림 — scale=2 HALF_UP")
    void band_roundingHalfUp() {
        // V=10001, bandWidth=15 → lowerBand = 10001 × 0.85 = 8500.85
        VrPosition pos = position(5, new BigDecimal("5000"), new BigDecimal("10001"), new BigDecimal("15.00"));
        assertThat(pos.lowerBand()).isEqualByComparingTo("8500.85");
        // upperBand = 10001 × 1.15 = 11501.15
        assertThat(pos.upperBand()).isEqualByComparingTo("11501.15");
    }

    // ── 매수 단가 계산 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("buyPrice(m) = lowerBand ÷ (holdings + m − 1), scale=2 HALF_UP")
    void buyPrice_calculatedCorrectly() {
        // holdings=5, lowerBand=8500, m=1 → divisor=5 → 8500/5=1700.00
        VrPosition pos = position(5, new BigDecimal("5000"), new BigDecimal("10000"), new BigDecimal("15.00"));
        assertThat(pos.buyPrice(1)).isEqualByComparingTo("1700.00");
        // m=2 → divisor=6 → 8500/6=1416.67 (HALF_UP)
        assertThat(pos.buyPrice(2)).isEqualByComparingTo("1416.67");
        // m=3 → divisor=7 → 8500/7=1214.29 (HALF_UP)
        assertThat(pos.buyPrice(3)).isEqualByComparingTo("1214.29");
    }

    // ── 매도 단가 계산 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("sellPrice(s) = upperBand ÷ (holdings − s + 1), scale=2 HALF_UP")
    void sellPrice_calculatedCorrectly() {
        // holdings=5, upperBand=11500, s=1 → divisor=5 → 11500/5=2300.00
        VrPosition pos = position(5, new BigDecimal("5000"), new BigDecimal("10000"), new BigDecimal("15.00"));
        assertThat(pos.sellPrice(1)).isEqualByComparingTo("2300.00");
        // s=2 → divisor=4 → 11500/4=2875.00
        assertThat(pos.sellPrice(2)).isEqualByComparingTo("2875.00");
        // s=5 → divisor=1 → 11500/1=11500.00
        assertThat(pos.sellPrice(5)).isEqualByComparingTo("11500.00");
    }

    // ── nextValue (V 갱신 실력공식) ────────────────────────────────────────────

    @Test
    @DisplayName("nextValue 케이스1: (V=10000, pool=5000, G=10, recurring=0, eval=9000) → 10341.89")
    void nextValue_case1_noRecurring() {
        // V + pool/G + recurring + (eval−V)/(2√G)
        // = 10000 + 500 + 0 + (9000−10000)/(2×√10)
        // = 10000 + 500 + 0 + (−1000)/6.32455...
        // = 10000 + 500 − 158.11... = 10341.89
        BigDecimal result = VrPosition.nextValue(
                new BigDecimal("10000"), new BigDecimal("5000"), 10, 0, new BigDecimal("9000"));
        assertThat(result).isEqualByComparingTo("10341.89");
    }

    @Test
    @DisplayName("nextValue 케이스2: (V=10000, pool=5000, G=20, recurring=−200, eval=12000) → 10273.61")
    void nextValue_case2_negativeRecurring() {
        // = 10000 + 250 + (−200) + (12000−10000)/(2×√20)
        // = 10000 + 250 − 200 + 2000/8.9442...
        // = 10000 + 250 − 200 + 223.61... = 10273.61
        BigDecimal result = VrPosition.nextValue(
                new BigDecimal("10000"), new BigDecimal("5000"), 20, -200, new BigDecimal("12000"));
        assertThat(result).isEqualByComparingTo("10273.61");
    }

    @Test
    @DisplayName("nextValue 평가금=V일 때 조정 항=0 — pool/G + recurring만 반영")
    void nextValue_evaluationEqualsValue_zeroAdjustment() {
        // eval=V=10000 → (eval−V)/(2√G) = 0 → V' = V + pool/G + recurring
        BigDecimal result = VrPosition.nextValue(
                new BigDecimal("10000"), new BigDecimal("4000"), 10, 100, new BigDecimal("10000"));
        // = 10000 + 400 + 100 + 0 = 10500.00
        assertThat(result).isEqualByComparingTo("10500.00");
    }

    // ── StrategyVrDetail: gradient / poolLimitRate ──────────────────────────

    @Test
    @DisplayName("gradient: recurringAmount < 0 → 20, 그 외 → 10")
    void strategyVrDetail_gradient() {
        UUID vid = UUID.randomUUID();
        // 인출(음수) → 고난도 G=20
        StrategyVrDetail detail1 = new StrategyVrDetail(vid, 4, new BigDecimal("15.00"), -100);
        assertThat(detail1.gradient()).isEqualTo(20);

        // 적립 없음 → G=10
        StrategyVrDetail detail2 = new StrategyVrDetail(vid, 4, new BigDecimal("15.00"), 0);
        assertThat(detail2.gradient()).isEqualTo(10);

        // 입금(양수) → G=10
        StrategyVrDetail detail3 = new StrategyVrDetail(vid, 4, new BigDecimal("15.00"), 200);
        assertThat(detail3.gradient()).isEqualTo(10);
    }

    @Test
    @DisplayName("poolLimitRate: recurringAmount에 따라 0.75/0.50/0.25")
    void strategyVrDetail_poolLimitRate() {
        UUID vid = UUID.randomUUID();
        // 입금(양수) → 높은 한도 0.75
        StrategyVrDetail d1 = new StrategyVrDetail(vid, 4, new BigDecimal("15.00"), 200);
        assertThat(d1.poolLimitRate()).isEqualByComparingTo("0.75");

        // 없음(0) → 기본 한도 0.50
        StrategyVrDetail d2 = new StrategyVrDetail(vid, 4, new BigDecimal("15.00"), 0);
        assertThat(d2.poolLimitRate()).isEqualByComparingTo("0.50");

        // 인출(음수) → 낮은 한도 0.25
        StrategyVrDetail d3 = new StrategyVrDetail(vid, 4, new BigDecimal("15.00"), -100);
        assertThat(d3.poolLimitRate()).isEqualByComparingTo("0.25");
    }

    // ── pool() / holdings() 위임 ────────────────────────────────────────────

    @Test
    @DisplayName("pool()은 balance.usdDeposit() 반환, holdings()는 balance.holdings() 반환")
    void pool_and_holdings_delegateToBalance() {
        VrPosition pos = position(7, new BigDecimal("3000"), new BigDecimal("8000"), new BigDecimal("20.00"));
        assertThat(pos.holdings()).isEqualTo(7);
        assertThat(pos.pool()).isEqualByComparingTo("3000");
    }
}
