package com.kista.domain.model.strategy;

import com.kista.domain.model.strategy.Strategy.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReverseModePosition 예산 소진 판정")
class ReverseModePositionTest {

    @Test
    @DisplayName("별지점이 있고 쿼터매수 예산으로 1주도 못 사면 예산 소진으로 판정한다")
    void isQuotaBuyExhausted_trueWhenBudgetTooSmall() {
        // usdDeposit=3.00 → 쿼터매수 예산 0.75, buyPrice=19.99 → floor(0.75/19.99)=0
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("3.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        assertThat(position.isQuotaBuyExhausted()).isTrue();
    }

    @Test
    @DisplayName("별지점이 아직 계산되지 않았으면 예산 소진이 아니다 (데이터 부족과 구분)")
    void isQuotaBuyExhausted_falseWhenStarPointMissing() {
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("3.00"),
                Ticker.SOXL, 20, null, false);

        assertThat(position.isQuotaBuyExhausted()).isFalse();
    }

    @Test
    @DisplayName("쿼터매수 예산이 충분하면 예산 소진이 아니다")
    void isQuotaBuyExhausted_falseWhenBudgetSufficient() {
        // usdDeposit=1000.00 → 쿼터매수 예산 250.00, buyPrice=19.99 → floor(250/19.99)=12 (>0)
        ReverseModePosition position = new ReverseModePosition(
                100, new BigDecimal("10.00"), new BigDecimal("1000.00"),
                Ticker.SOXL, 20, new BigDecimal("20.00"), false);

        assertThat(position.isQuotaBuyExhausted()).isFalse();
    }
}
