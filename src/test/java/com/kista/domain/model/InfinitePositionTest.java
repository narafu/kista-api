package com.kista.domain.model;

import com.kista.domain.model.strategy.AccountBalance;
import com.kista.domain.model.strategy.InfinitePosition;
import com.kista.domain.model.strategy.Strategy.Ticker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("InfinitePosition 무한매수법 변수 계산 검증")
class InfinitePositionTest {

    @Test
    @DisplayName("holdings=0: 최근 종가(18)를 averagePrice 대용으로 사용(현재가 20 무관), currentRound=0, priceOffsetRate=0.2000 (SOXL)")
    void case_q0() {
        // averagePrice=prevClose=18, purchaseAmount=0, totalAssets=2000, unitAmount=100.00, currentRound=0.0
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("2000"));
        InfinitePosition pos = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("18"), 20);

        assertThat(pos.averagePrice()).isEqualByComparingTo("18"); // 최근 종가 기준 (현재가 20 아님)
        assertThat(pos.holdings()).isEqualTo(0);
        assertThat(pos.purchaseAmount()).isEqualByComparingTo("0");
        assertThat(pos.totalAssets()).isEqualByComparingTo("2000");
        assertThat(pos.currentRound()).isEqualTo(0.0);
        assertThat(pos.unitAmount()).isEqualByComparingTo("100.00");
        assertThat(pos.priceOffsetRate()).isEqualByComparingTo("0.2000"); // SOXL targetProfitRate=0.20
        assertThat(pos.usdDeposit()).isEqualByComparingTo("2000");
        assertThat(pos.referencePrice()).isEqualByComparingTo("21.60"); // 18 × (1+0.2000)
        assertThat(pos.targetPrice()).isEqualByComparingTo("21.60");
    }

    @Test
    @DisplayName("holdings=10: currentRound=1.33, priceOffsetRate=0.17 (SOXL)")
    void case_q10() {
        // averagePrice=20, purchaseAmount=200, totalAssets=3000, unitAmount=150.00, currentRound=200/150=1.33
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20"), new BigDecimal("2800"));
        InfinitePosition pos = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("19"), 20);

        assertThat(pos.averagePrice()).isEqualByComparingTo("20");
        assertThat(pos.holdings()).isEqualTo(10);
        assertThat(pos.purchaseAmount()).isEqualByComparingTo("200");
        assertThat(pos.totalAssets()).isEqualByComparingTo("3000");
        assertThat(pos.currentRound()).isCloseTo(1.33, within(0.01));
        assertThat(pos.unitAmount()).isEqualByComparingTo("150.00");
        assertThat(pos.priceOffsetRate()).isEqualByComparingTo("0.17"); // 0.20 × (1 - 2×1.33/20) = 0.1734 → scale=2 반올림 0.17
        assertThat(pos.usdDeposit()).isEqualByComparingTo("2800");
        assertThat(pos.referencePrice()).isEqualByComparingTo("23.40"); // 20 × (1+0.17) = 23.40
        assertThat(pos.targetPrice()).isEqualByComparingTo("24.00");
    }

    @Test
    @DisplayName("holdings=100: currentRound=5.0, priceOffsetRate=0.1000 (SOXL)")
    void case_q100() {
        // averagePrice=5, purchaseAmount=500, totalAssets=2000, unitAmount=100.00, currentRound=500/100=5.0
        AccountBalance balance = new AccountBalance(100, new BigDecimal("5"), new BigDecimal("1500"));
        InfinitePosition pos = new InfinitePosition(balance, Ticker.SOXL, new BigDecimal("5.8"), 20);

        assertThat(pos.averagePrice()).isEqualByComparingTo("5");
        assertThat(pos.holdings()).isEqualTo(100);
        assertThat(pos.purchaseAmount()).isEqualByComparingTo("500");
        assertThat(pos.totalAssets()).isEqualByComparingTo("2000");
        assertThat(pos.currentRound()).isEqualTo(5.0);
        assertThat(pos.unitAmount()).isEqualByComparingTo("100.00");
        assertThat(pos.priceOffsetRate()).isEqualByComparingTo("0.1000");
        assertThat(pos.usdDeposit()).isEqualByComparingTo("1500");
        assertThat(pos.referencePrice()).isEqualByComparingTo("5.50"); // 5 × (1+0.1000)
        assertThat(pos.targetPrice()).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("TQQQ: targetProfitRate=0.15, targetPrice=A×1.15")
    void case_tqqq_targetProfitRate() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000"));
        InfinitePosition pos = new InfinitePosition(balance, Ticker.TQQQ, new BigDecimal("50"), 20);

        assertThat(pos.priceOffsetRate()).isEqualByComparingTo("0.1500"); // TQQQ targetProfitRate=0.15
        assertThat(pos.referencePrice()).isEqualByComparingTo("57.50"); // 50×1.15
        assertThat(pos.targetPrice()).isEqualByComparingTo("57.50");
    }

    @Test
    @DisplayName("earlyBuyQty1/earlyBuyQty2: FLOOR 결과가 0이어도 최소 1주 보장")
    void earlyBuyQty_minimumOneShare() {
        // unitAmount=10, price1=100 → (10/2)/100 = floor(0.05) = 0 → 최소 1
        int qty1 = InfinitePosition.earlyBuyQty1(new BigDecimal("10"), new BigDecimal("100"));
        assertThat(qty1).isEqualTo(1);

        // unitAmount=10, price1=100, qty1=1 → (10 - 100*1)/price2 = 음수/200 → 최소 1
        int qty2 = InfinitePosition.earlyBuyQty2(new BigDecimal("10"), new BigDecimal("100"), 1, new BigDecimal("200"));
        assertThat(qty2).isEqualTo(1);
    }
}
