package com.kista.tradingcore.matching.domain.model;

import com.kista.tradingcore.matching.domain.model.AccountBalance;
import com.kista.tradingcore.matching.domain.model.InfinitePosition;
import com.kista.shared.kernel.StrategyTicker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
        InfinitePosition pos = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("18"), 20);

        assertThat(pos.averagePrice()).isEqualByComparingTo("18"); // 최근 종가 기준 (현재가 20 아님)
        assertThat(pos.holdings()).isEqualTo(0);
        assertThat(pos.purchaseAmount()).isEqualByComparingTo("0");
        assertThat(pos.totalAssets()).isEqualByComparingTo("2000");
        assertThat(pos.currentRound()).isEqualTo(0.0);
        assertThat(pos.unitAmount()).isEqualByComparingTo("100.00");
        assertThat(pos.priceOffsetRate()).isEqualByComparingTo("0.2000"); // SOXL targetProfitRate=0.20
        assertThat(pos.deposit()).isEqualByComparingTo("2000");
        assertThat(pos.referencePrice()).isEqualByComparingTo("21.60"); // 18 × (1+0.2000)
        assertThat(pos.targetPrice()).isEqualByComparingTo("21.60");
    }

    @Test
    @DisplayName("holdings=10: currentRound=1.333…, priceOffsetRate=0.17333… (SOXL) — 비율은 반올림하지 않는다")
    void case_q10() {
        // averagePrice=20, purchaseAmount=200, totalAssets=3000, unitAmount=150.00, currentRound=200/150=1.333…
        AccountBalance balance = new AccountBalance(10, new BigDecimal("20"), new BigDecimal("2800"));
        InfinitePosition pos = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("19"), 20);

        assertThat(pos.averagePrice()).isEqualByComparingTo("20");
        assertThat(pos.holdings()).isEqualTo(10);
        assertThat(pos.purchaseAmount()).isEqualByComparingTo("200");
        assertThat(pos.totalAssets()).isEqualByComparingTo("3000");
        assertThat(pos.currentRound()).isCloseTo(1.3333333, within(1e-6));
        // 화면용 static 헬퍼도 같은 회차를 낸다(UI 회차와 주문 판정이 어긋나지 않게)
        assertThat(InfinitePosition.calcCurrentRound(new BigDecimal("20"), 10, new BigDecimal("2800"), 20))
                .isEqualTo(pos.currentRound());
        assertThat(pos.unitAmount()).isEqualByComparingTo("150.00");
        assertThat(pos.priceOffsetRate()).isCloseTo(new BigDecimal("0.1733333"), within(new BigDecimal("1e-6"))); // 0.20 × (1 - 2×1.333…/20)
        assertThat(pos.deposit()).isEqualByComparingTo("2800");
        assertThat(pos.referencePrice()).isEqualByComparingTo("23.47"); // 20 × 1.17333… = 23.4667 → 가격만 2자리 반올림
        assertThat(pos.targetPrice()).isEqualByComparingTo("24.00");
    }

    // 문헌 계산기 검증값(docs/reference/quantstack/infinite-buying-v4.md B3): TQQQ 40분할, 평단 50
    // unitAmount가 500으로 떨어지도록 (보유, 예수금)을 잡는다 — T = 매입액 ÷ 500
    // T=20(분할수/2)은 문헌 계산기 표가 전반전, 사이트 주문 카드가 후반전으로 다르게 처리한다(E절 1번) — KISTA는 후반으로 둔다
    @ParameterizedTest(name = "T={0}: 별%={3}, 별지점={4}, 전반={5}")
    @CsvSource({
            "1,  10,  19500, 0.1425,  57.13, true",
            "10, 100, 15000, 0.075,   53.75, true",
            "20, 200, 10000, 0,       50.00, false",
            "21, 210, 9500,  -0.0075, 49.63, false",
            "30, 300, 5000,  -0.075,  46.25, false",
            "40, 400, 0,     -0.15,   42.50, false"
    })
    @DisplayName("별%·별지점: 문헌 계산기 값(TQQQ 40분할, 평단 50)과 일치한다")
    void starPoint_matchesReferenceCalculator(int round, int holdings, int deposit, String rate, String starPoint,
                                              boolean early) {
        InfinitePosition pos = new InfinitePosition(
                new AccountBalance(holdings, new BigDecimal("50"), new BigDecimal(deposit)),
                StrategyTicker.TQQQ, new BigDecimal("50"), 40);

        assertThat(pos.unitAmount()).isEqualByComparingTo("500.00");
        assertThat(pos.currentRound()).isEqualTo(round);
        assertThat(pos.priceOffsetRate()).isEqualByComparingTo(rate);
        assertThat(pos.referencePrice()).isEqualByComparingTo(starPoint);
        assertThat(pos.isEarlyStage()).isEqualTo(early);
    }

    @Test
    @DisplayName("holdings=100: currentRound=5.0, priceOffsetRate=0.1000 (SOXL)")
    void case_q100() {
        // averagePrice=5, purchaseAmount=500, totalAssets=2000, unitAmount=100.00, currentRound=500/100=5.0
        AccountBalance balance = new AccountBalance(100, new BigDecimal("5"), new BigDecimal("1500"));
        InfinitePosition pos = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("5.8"), 20);

        assertThat(pos.averagePrice()).isEqualByComparingTo("5");
        assertThat(pos.holdings()).isEqualTo(100);
        assertThat(pos.purchaseAmount()).isEqualByComparingTo("500");
        assertThat(pos.totalAssets()).isEqualByComparingTo("2000");
        assertThat(pos.currentRound()).isEqualTo(5.0);
        assertThat(pos.unitAmount()).isEqualByComparingTo("100.00");
        assertThat(pos.priceOffsetRate()).isEqualByComparingTo("0.1000");
        assertThat(pos.deposit()).isEqualByComparingTo("1500");
        assertThat(pos.referencePrice()).isEqualByComparingTo("5.50"); // 5 × (1+0.1000)
        assertThat(pos.targetPrice()).isEqualByComparingTo("6.00");
    }

    @Test
    @DisplayName("TQQQ: targetProfitRate=0.15, targetPrice=A×1.15")
    void case_tqqq_targetProfitRate() {
        AccountBalance balance = new AccountBalance(0, null, new BigDecimal("1000"));
        InfinitePosition pos = new InfinitePosition(balance, StrategyTicker.TQQQ, new BigDecimal("50"), 20);

        assertThat(pos.priceOffsetRate()).isEqualByComparingTo("0.1500"); // TQQQ targetProfitRate=0.15
        assertThat(pos.referencePrice()).isEqualByComparingTo("57.50"); // 50×1.15
        assertThat(pos.targetPrice()).isEqualByComparingTo("57.50");
    }

    @Test
    @DisplayName("earlyBuyQuantity1: FLOOR 결과가 0이어도 최소 1주 보장, earlyBuyQuantity2: 잔여가 음수면 0주")
    void earlyBuyQuantity_minimumOneShare() {
        // unitAmount=10, price1=100 → (10/2)/100 = floor(0.05) = 0 → 최소 1
        int quantity1 = InfinitePosition.earlyBuyQuantity1(new BigDecimal("10"), new BigDecimal("100"));
        assertThat(quantity1).isEqualTo(1);

        // unitAmount=10, price1=100, quantity1=1 → (10 - 100*1)/price2 = 음수/200 → 잔여 0 이하라 0주
        int quantity2 = InfinitePosition.earlyBuyQuantity2(new BigDecimal("10"), new BigDecimal("100"), 1, new BigDecimal("200"));
        assertThat(quantity2).isZero();
    }

    @Test
    @DisplayName("lateBuyQuantity: FLOOR 결과가 0이어도 최소 1주 보장")
    void lateBuyQuantity_minimumOneShare() {
        // unitAmount=10, price=100 → floor(0.1) = 0 → 최소 1
        int quantity = InfinitePosition.lateBuyQuantity(new BigDecimal("10"), new BigDecimal("100"));
        assertThat(quantity).isEqualTo(1);
    }

    // --- nextReverseMode: 리버스모드 상태 전이 검증 ---

    @Test
    @DisplayName("nextReverseMode: 일반모드 + isFinalRound → 리버스모드 진입")
    void nextReverseMode_enters_when_finalRound() {
        // unitAmount=100.00, deposit=50 → isFinalRound=true (unitAmount > deposit)
        // holdings=5, avgPrice=20 → purchaseAmount=100, totalAssets=150, unitAmount=7.50
        // unitAmount(7.50) > deposit(50)? → No... 재계산: deposit=50, holdings=5, avg=20
        // totalAssets=50+100=150, unitAmount=150/20=7.50 → isFinalRound: 7.50>50? No
        // 소진 조건은 unitAmount > deposit: holdings=18, avg=22, deposit=22 → purchase=396, total=418, unit=20.90 > 22 → true
        AccountBalance balance = new AccountBalance(18, new BigDecimal("22"), new BigDecimal("22"));
        InfinitePosition pos = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("22"), 20);

        // unitAmount(20.90) > deposit(22)? No... 재계산
        // totalAssets=22+(22*18)=22+396=418, unitAmount=418/20=20.90 → 20.90 < 22 → isFinalRound=false
        // holdings=19, avg=22, deposit=8 → purchase=418, total=426, unit=21.30 > 8 → true
        AccountBalance balance2 = new AccountBalance(19, new BigDecimal("22"), new BigDecimal("8"));
        InfinitePosition pos2 = new InfinitePosition(balance2, StrategyTicker.SOXL, new BigDecimal("22"), 20);
        assertThat(pos2.isFinalRound()).isTrue();
        assertThat(pos2.nextReverseMode(false)).isTrue(); // 소진 발동 → 리버스모드 진입
    }

    @Test
    @DisplayName("isFinalRound: unitAmount<=원장이어도 기준가 1주가 원장을 넘으면 최종회차")
    void finalRoundWhenOneShareAtReferenceExceedsDeposit() {
        // holdings=10, avg=41.5, deposit=25 → purchase=415, total=440, unit=22 (<25), round≈18.86 → 후반
        AccountBalance balance = new AccountBalance(10, new BigDecimal("41.5"), new BigDecimal("25"));
        InfinitePosition pos = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("40"), 20);

        assertThat(pos.unitAmount()).isLessThanOrEqualTo(pos.deposit());
        assertThat(pos.referencePrice()).isGreaterThan(pos.deposit());
        assertThat(pos.isFinalRound()).isTrue();
    }

    @Test
    @DisplayName("nextReverseMode: 리버스모드 + 종가 회복 → 일반모드 복귀")
    void nextReverseMode_exits_when_priceRecovered() {
        // SOXL targetProfitRate=0.20 → threshold = avgPrice × (1 - 0.20) = 22 × 0.80 = 17.60
        // closingPrice=18.00 ≥ threshold(17.60) → shouldExit=true → 일반모드 복귀
        AccountBalance balance = new AccountBalance(10, new BigDecimal("22"), new BigDecimal("100"));
        InfinitePosition pos = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("18.00"), 20);

        assertThat(pos.nextReverseMode(true)).isFalse(); // 종가 회복 → 일반모드 복귀
    }

    @Test
    @DisplayName("nextReverseMode: 리버스모드 + 종가 미회복 → 리버스모드 유지")
    void nextReverseMode_stays_when_priceNotRecovered() {
        // SOXL targetProfitRate=0.20 → threshold = 22 × 0.80 = 17.60
        // closingPrice=15.00 < threshold(17.60) → shouldExit=false → 리버스모드 유지
        AccountBalance balance = new AccountBalance(10, new BigDecimal("22"), new BigDecimal("100"));
        InfinitePosition pos = new InfinitePosition(balance, StrategyTicker.SOXL, new BigDecimal("15.00"), 20);

        assertThat(pos.nextReverseMode(true)).isTrue(); // 종가 미회복 → 리버스모드 유지
    }

    @Test
    @DisplayName("earlyBuyQuantity2: 매수①이 unitAmount를 다 쓰면 0주, 잔여가 있으면 최소 1주")
    void earlyBuyQuantity2_zeroWhenFirstBuyConsumesUnit() {
        assertThat(InfinitePosition.earlyBuyQuantity2(new BigDecimal("5.00"), new BigDecimal("60"), 1, new BigDecimal("72.00"))).isZero();
        assertThat(InfinitePosition.earlyBuyQuantity2(new BigDecimal("100.00"), new BigDecimal("100"), 1, new BigDecimal("120.00"))).isZero();
        assertThat(InfinitePosition.earlyBuyQuantity2(new BigDecimal("100.00"), new BigDecimal("60"), 1, new BigDecimal("72.00"))).isEqualTo(1);
        assertThat(InfinitePosition.earlyBuyQuantity2(new BigDecimal("1000.00"), new BigDecimal("50"), 10, new BigDecimal("60.00"))).isEqualTo(8);
    }

    // 평단 1 고정, totalAssets=holdings+deposit — 분할수별 회차 경계 검증용 헬퍼
    private static InfinitePosition posAtAvg1(int holdings, int deposit, int divisionCount) {
        return new InfinitePosition(new AccountBalance(holdings, BigDecimal.ONE, new BigDecimal(deposit)),
                StrategyTicker.SOXL, BigDecimal.ONE, divisionCount);
    }

    @ParameterizedTest(name = "20분할 holdings={0} → currentRound={2}, priceOffsetRate={3}, early={4}")
    @CsvSource({
            "976, 1024, 9.76, 0.0048, true",   // 별%를 2자리로 반올림하던 때는 0.00으로 후반 판정됐다
            "999, 1001, 9.99, 0.0002, true",
            "1000, 1000, 10.00, 0, false",     // 정확히 분할수/2 — 후반
            "1001, 999, 10.01, -0.0002, false",
            "1100, 900, 11.00, -0.02, false"
    })
    @DisplayName("전·후반 경계는 정확히 분할수/2 회차다 (20분할 SOXL 0.20)")
    void earlyStage_boundaryIsHalfDivision_20div(int holdings, int deposit, String round, String rate, boolean early) {
        InfinitePosition pos = posAtAvg1(holdings, deposit, 20);

        assertThat(pos.currentRound()).isEqualTo(Double.parseDouble(round));
        assertThat(pos.priceOffsetRate()).isEqualByComparingTo(rate);
        assertThat(pos.isEarlyStage()).isEqualTo(early);
    }

    @ParameterizedTest(name = "{0}분할 holdings={1} → currentRound={3}, rate={4}, early={5}")
    @CsvSource({
            "30, 1400, 1600, 14.00, 0.0133333333, true", // 0.20 × (1 - 28/30) — 15회차 직전 전반
            "30, 1500, 1500, 15.00, 0, false",           // 15회차 경계 후반
            "40, 1900, 2100, 19.00, 0.01, true",         // 0.20 × (1 - 38/40) — 20회차 직전 전반
            "40, 2000, 2000, 20.00, 0, false"            // 20회차 경계 후반
    })
    @DisplayName("priceOffsetRate 0 경계가 분할수/2 회차로 이동한다 (30분할 15, 40분할 20)")
    void priceOffsetRate_zeroBoundary_movesWithDivisionCount(int div, int holdings, int deposit, String round,
                                                            String rate, boolean early) {
        InfinitePosition pos = posAtAvg1(holdings, deposit, div);

        assertThat(pos.unitAmount()).isEqualByComparingTo("100.00"); // totalAssets=100×div
        assertThat(pos.currentRound()).isEqualTo(Double.parseDouble(round));
        assertThat(pos.priceOffsetRate()).isCloseTo(new BigDecimal(rate), within(new BigDecimal("1e-9")));
        assertThat(pos.isEarlyStage()).isEqualTo(early);
    }

    @ParameterizedTest(name = "{0}분할 holdings={1} deposit={2} → isFinalRound={3}")
    @CsvSource({
            "30, 2900, 100, false", // 29.00회차, unit(100) == deposit(100)
            "30, 2910, 90, true",   // 29.10회차, unit(100) > deposit(90)
            "40, 3900, 100, false", // 39.00회차
            "40, 3910, 90, true"    // 39.10회차
    })
    @DisplayName("isFinalRound: unitAmount > deposit 경계가 분할수-1 회차 부근에 있다 (30분할 29, 40분할 39)")
    void isFinalRound_boundary_30_40div(int div, int holdings, int deposit, boolean expected) {
        assertThat(posAtAvg1(holdings, deposit, div).isFinalRound()).isEqualTo(expected);
    }

    @Test
    @DisplayName("30분할 수량: 전반(14회차) 평단 매수 50주·기준가 매수 49주, 후반(15회차) 100주")
    void quantities_30div() {
        InfinitePosition early = posAtAvg1(1400, 1600, 30); // unit=100, 평단 1, 기준가 1.01
        int byAvg = early.calcEarlyBuyQuantityByAvgPrice();

        assertThat(byAvg).isEqualTo(50);                                       // floor(50/1)
        assertThat(early.calcEarlyBuyQuantityByRefPrice(byAvg)).isEqualTo(49); // floor(50/1.01)
        assertThat(posAtAvg1(1500, 1500, 30).calcLateBuyQuantity()).isEqualTo(100); // floor(100/1.00)
    }

    @Test
    @DisplayName("40분할 수량: 전반(19회차) 평단 매수 50주, 후반(20회차) 100주")
    void quantities_40div() {
        assertThat(posAtAvg1(1900, 2100, 40).calcEarlyBuyQuantityByAvgPrice()).isEqualTo(50);
        assertThat(posAtAvg1(2000, 2000, 40).calcLateBuyQuantity()).isEqualTo(100);
    }
}
