package com.kista.domain.model.broker;

import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PresentBalanceResult.aggregateToss 단위 테스트")
class PresentBalanceResultTest {

    // 보유 종목 1건 — 종목별 평가·손익·수익률 및 KRW 총자산 계산 검증
    @Test
    @DisplayName("단일 보유 종목: 평가금액·손익·수익률·KRW 총자산 계산")
    void aggregateToss_singleHolding_computesAllTotals() {
        // 5주 @ 평단 20.00, 현재가 22.00, USD예수금 1000, KRW예수금 140000, 환율 1400
        var holding = new PresentBalanceResult.TossHolding(
                Ticker.SOXL, 5, new BigDecimal("20.00"), new BigDecimal("22.00"));

        PresentBalanceResult result = PresentBalanceResult.aggregateToss(
                List.of(holding), new BigDecimal("1000.00"), new BigDecimal("140000"), new BigDecimal("1400"));

        // 종목별: eval = 22×5 = 110.00, 손익 = (22-20)×5 = 10.00, 수익률 = 2/20×100 = 10.00%
        assertThat(result.items()).hasSize(1);
        PresentBalanceResult.Item item = result.items().get(0);
        assertThat(item.ticker()).isEqualTo(Ticker.SOXL);
        assertThat(item.holdings()).isEqualTo(5);
        assertThat(item.evalAmountUsd()).isEqualByComparingTo("110.00");
        assertThat(item.profitLossUsd()).isEqualByComparingTo("10.00");
        assertThat(item.profitRate()).isEqualByComparingTo("10.00");
        assertThat(item.exchangeCode()).isEqualTo("AMEX");

        // totalAssetKrw = (110 + 1000)×1400 + 140000 = 1,554,000 + 140,000 = 1,694,000
        assertThat(result.totalAssetUsd()).isEqualByComparingTo("1694000");
        // totalEvalProfitKrw = 10×1400 = 14,000
        assertThat(result.totalEvalProfit()).isEqualByComparingTo("14000");
        // 매입금액KRW = 100×1400 = 140,000 → 수익률 = 14000/140000×100 = 10.00%
        assertThat(result.totalReturnRate()).isEqualByComparingTo("10.00");
        // krwAsUsd = 140000/1400 = 100.00 → totalUsdDeposit = 1000 + 100 = 1100.00
        assertThat(result.usdDepositActual()).isEqualByComparingTo("1100.00");
        assertThat(result.exchangeRateKrwPerUsd()).isEqualByComparingTo("1400");
    }

    // 환율 0 경계값 — KRW 환산 불가 시 KRW예수금만 총자산, 손익·수익률 0
    @Test
    @DisplayName("환율 0: 총자산은 KRW예수금만, 손익·수익률 0")
    void aggregateToss_zeroRate_fallsBackToKrwDeposit() {
        var holding = new PresentBalanceResult.TossHolding(
                Ticker.SOXL, 5, new BigDecimal("20.00"), new BigDecimal("22.00"));

        PresentBalanceResult result = PresentBalanceResult.aggregateToss(
                List.of(holding), new BigDecimal("1000.00"), new BigDecimal("50000"), BigDecimal.ZERO);

        // 종목별 계산은 환율과 무관하게 동일하게 수행됨
        assertThat(result.items().get(0).evalAmountUsd()).isEqualByComparingTo("110.00");
        // 환율 0 → totalAssetKrw = krwDeposit = 50,000
        assertThat(result.totalAssetUsd()).isEqualByComparingTo("50000");
        assertThat(result.totalEvalProfit()).isEqualByComparingTo("0");
        assertThat(result.totalReturnRate()).isEqualByComparingTo("0");
        // 환율 0 → krwAsUsd = 0 → totalUsdDeposit = usdDeposit
        assertThat(result.usdDepositActual()).isEqualByComparingTo("1000.00");
    }

    // 평단 0 경계값 — 0 나눗셈 방지, 종목 수익률 0
    @Test
    @DisplayName("평단가 0: 종목 수익률 0 (0 나눗셈 방지)")
    void aggregateToss_zeroAvgPrice_returnsZeroProfitRate() {
        var holding = new PresentBalanceResult.TossHolding(
                Ticker.SOXL, 3, BigDecimal.ZERO, new BigDecimal("15.00"));

        PresentBalanceResult result = PresentBalanceResult.aggregateToss(
                List.of(holding), new BigDecimal("100.00"), new BigDecimal("14000"), new BigDecimal("1400"));

        PresentBalanceResult.Item item = result.items().get(0);
        assertThat(item.profitRate()).isEqualByComparingTo("0");
        // eval = 15×3 = 45.00, 손익 = (15-0)×3 = 45.00
        assertThat(item.evalAmountUsd()).isEqualByComparingTo("45.00");
        assertThat(item.profitLossUsd()).isEqualByComparingTo("45.00");
        // 매입금액 0 → totalReturnRate = 0 (0 나눗셈 방지)
        assertThat(result.totalReturnRate()).isEqualByComparingTo("0");
    }

    // 보유 종목 없음 — 예수금만 반영
    @Test
    @DisplayName("보유 종목 없음: 예수금만 반영")
    void aggregateToss_noHoldings_depositsOnly() {
        PresentBalanceResult result = PresentBalanceResult.aggregateToss(
                List.of(), new BigDecimal("500.00"), new BigDecimal("70000"), new BigDecimal("1400"));

        assertThat(result.items()).isEmpty();
        // totalAssetKrw = (0 + 500)×1400 + 70000 = 700000 + 70000 = 770,000
        assertThat(result.totalAssetUsd()).isEqualByComparingTo("770000");
        assertThat(result.totalEvalProfit()).isEqualByComparingTo("0");
        assertThat(result.totalReturnRate()).isEqualByComparingTo("0");
        // totalUsdDeposit = 500 + 70000/1400(=50.00) = 550.00
        assertThat(result.usdDepositActual()).isEqualByComparingTo("550.00");
    }
}
