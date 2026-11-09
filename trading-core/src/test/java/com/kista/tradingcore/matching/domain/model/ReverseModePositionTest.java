package com.kista.tradingcore.matching.domain.model;

import com.kista.tradingcore.matching.domain.model.ReverseModePosition;
import com.kista.shared.kernel.StrategyTicker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReverseModePosition 예산 소진 판정")
class ReverseModePositionTest {

    @Test
    @DisplayName("별지점이 있고 쿼터매수 예산으로 1주도 못 사면 예산 소진으로 판정한다")
    void isQuotaBuyExhausted_trueWhenBudgetTooSmall() {
        // deposit=3.00 → 쿼터매수 예산 0.75, buyPrice=19.99 → floor(0.75/19.99)=0
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("3.00"),
                StrategyTicker.SOXL, 20, new BigDecimal("20.00"), false);

        assertThat(position.isQuotaBuyExhausted()).isTrue();
    }

    @Test
    @DisplayName("별지점이 아직 계산되지 않았으면 예산 소진이 아니다 (데이터 부족과 구분)")
    void isQuotaBuyExhausted_falseWhenStarPointMissing() {
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("3.00"),
                StrategyTicker.SOXL, 20, null, false);

        assertThat(position.isQuotaBuyExhausted()).isFalse();
    }

    @Test
    @DisplayName("쿼터매수 예산이 충분하면 예산 소진이 아니다")
    void isQuotaBuyExhausted_falseWhenBudgetSufficient() {
        // deposit=1000.00 → 쿼터매수 예산 250.00, buyPrice=19.99 → floor(250/19.99)=12 (>0)
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("1000.00"),
                StrategyTicker.SOXL, 20, new BigDecimal("20.00"), false);

        assertThat(position.isQuotaBuyExhausted()).isFalse();
    }

    @ParameterizedTest(name = "{0}분할 holdings={1} → 매도 수량 {2}")
    @CsvSource({
            "20, 100, 10",
            "30, 100, 6",   // divisor 15 → 100/15
            "40, 100, 5",   // divisor 20
            "30, 14, 0",    // holdings < divisor
            "30, 15, 1"
    })
    @DisplayName("리버스 MOC/LOC 매도 수량은 holdings/(divisionCount/2) 정수 나눗셈이다")
    void sellQuantity_dividesByHalfDivisionCount(int div, int holdings, int expected) {
        ReverseModePosition position = new ReverseModePosition(
                holdings, new BigDecimal("10.00"), new BigDecimal("1000.00"),
                StrategyTicker.SOXL, div, new BigDecimal("20.00"), false);

        assertThat(position.calcMocSellQuantity()).isEqualTo(expected);
        assertThat(position.calcLocSellQuantity()).isEqualTo(expected);
    }

    @Test
    @DisplayName("쿼터매수 수량 헬퍼는 쿼터 예산(원장/4) ÷ 매수가 내림이고 별지점 경로와 같은 값을 낸다")
    void quotaBuyQuantity_sharedByStarPointAndCap() {
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("1000.00"),
                StrategyTicker.SOXL, 20, new BigDecimal("100.00"), false);

        // 별지점 경로: 250 / 99.99 = 2
        assertThat(position.calcLocBuyQuantity())
                .isEqualTo(ReverseModePosition.quotaBuyQuantity(new BigDecimal("1000.00"), new BigDecimal("99.99")))
                .isEqualTo(2);
        // 캡 경로: 250 / 52.50 = 4
        assertThat(ReverseModePosition.quotaBuyQuantity(new BigDecimal("1000.00"), new BigDecimal("52.50"))).isEqualTo(4);
    }

    @Test
    @DisplayName("쿼터매수 금액은 예수금÷4를 USD 센트(소수 2자리)로 HALF_UP 반올림한다")
    void calcLocBuyAmount_roundsToCents() {
        // 100.01 ÷ 4 = 25.0025 → 25.00
        ReverseModePosition position = new ReverseModePosition(
                10, new BigDecimal("10.00"), new BigDecimal("100.01"),
                StrategyTicker.SOXL, 20, new BigDecimal("20.00"), false);

        assertThat(position.calcLocBuyAmount()).isEqualTo(new BigDecimal("25.00"));
    }

    @Test
    @DisplayName("리버스 종료 임계값은 평단×(1−목표수익률)을 USD 센트로 반올림해 비교한다")
    void shouldExit_thresholdRoundedToCents() {
        // 목표수익률 0 → 임계값 = 100.003 → 100.00 (HALF_UP), 종가 100.001 ≥ 100.00 이므로 종료
        assertThat(ReverseModePosition.shouldExit(
                new BigDecimal("100.003"), new BigDecimal("100.001"), BigDecimal.ZERO)).isTrue();
    }
}
