package com.kista.tradingcore.matching.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DcaIndicatorsTest {

    private static List<BigDecimal> closes(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    void sma_averages_last_period_closes() {
        assertThat(DcaIndicators.sma(closes("1", "2", "3", "4", "5"), 3)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("4.00"));
    }

    @Test
    void sma_rounds_half_up_to_two_decimals() {
        assertThat(DcaIndicators.sma(closes("1", "1", "2"), 3)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("1.33"));
    }

    @Test
    void sma_insufficient_is_empty() {
        assertThat(DcaIndicators.sma(closes("1", "2"), 3)).isEmpty();
    }

    @Test
    void rsi_matches_wilder_reference() {
        // StockCharts 예제 데이터 — 공개값 70.53/66.32는 중간값 반올림 차이, 비반올림 Wilder 손계산은 70.46/66.25
        List<BigDecimal> base = closes("44.34", "44.09", "44.15", "43.61", "44.33", "44.83", "45.10", "45.42",
                "45.84", "46.08", "45.89", "46.03", "45.61", "46.28", "46.28");
        assertThat(DcaIndicators.rsiWilder(base, 14).orElseThrow().doubleValue()).isCloseTo(70.46, within(0.01));
        List<BigDecimal> next = new java.util.ArrayList<>(base);
        next.add(new BigDecimal("46.00"));
        assertThat(DcaIndicators.rsiWilder(next, 14).orElseThrow().doubleValue()).isCloseTo(66.25, within(0.01));
    }

    @Test
    void rsi_only_gains_is_100() {
        List<BigDecimal> rising = IntStream.rangeClosed(1, 20).mapToObj(BigDecimal::valueOf).toList();
        assertThat(DcaIndicators.rsiWilder(rising, 14)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("100.00"));
    }

    @Test
    void rsi_only_losses_is_0() {
        List<BigDecimal> falling = IntStream.rangeClosed(1, 20).mapToObj(i -> BigDecimal.valueOf(100 - i)).toList();
        assertThat(DcaIndicators.rsiWilder(falling, 14)).hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("0.00"));
    }

    @Test
    void rsi_flat_series_is_50() {
        assertThat(DcaIndicators.rsiWilder(Collections.nCopies(20, new BigDecimal("10")), 14))
                .hasValueSatisfying(v -> assertThat(v).isEqualByComparingTo("50.00"));
    }

    @Test
    void rsi_needs_period_plus_one_closes() {
        assertThat(DcaIndicators.rsiWilder(Collections.nCopies(14, BigDecimal.ONE), 14)).isEmpty();
        assertThat(DcaIndicators.rsiWilder(Collections.nCopies(15, BigDecimal.ONE), 14)).isPresent();
    }
}
