package com.kista.stats.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnMetricsTest {

    @Test
    void normalize_기본케이스() {
        // 150 / 100 * 100 = 150 (2배 상승했을 때 200이 아니라 150이 되는 흔한 실수를 방지)
        BigDecimal result = ReturnMetrics.normalize(new BigDecimal("150"), new BigDecimal("100"));

        assertThat(result).isEqualByComparingTo("150");
    }

    @Test
    void cumulativeReturn_변동없음() {
        BigDecimal result = ReturnMetrics.cumulativeReturn(new BigDecimal("100"));

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void cumulativeReturn_상승50퍼센트() {
        BigDecimal result = ReturnMetrics.cumulativeReturn(new BigDecimal("150"));

        assertThat(result).isEqualByComparingTo("0.5");
    }

    @Test
    void cumulativeReturn_하락50퍼센트() {
        BigDecimal result = ReturnMetrics.cumulativeReturn(new BigDecimal("50"));

        assertThat(result).isEqualByComparingTo("-0.5");
    }

    @Test
    void annualizedReturn_지수가0이하면_마이너스100퍼센트_고정() {
        BigDecimal result = ReturnMetrics.annualizedReturn(BigDecimal.ZERO, 4.0);

        // scale(10)까지 정확히 -1.0000000000 이어야 함 (Math.pow NaN 회피 경로)
        assertThat(result).isEqualByComparingTo(BigDecimal.ONE.negate());
        assertThat(result.scale()).isEqualTo(10);
    }

    @Test
    void annualizedReturn_양수케이스() {
        // 1년간 지수가 100->150(50% 상승), periodsPerYear=1 -> 연환산도 50%
        BigDecimal result = ReturnMetrics.annualizedReturn(new BigDecimal("150"), 1.0);

        assertThat(result).isEqualByComparingTo("0.5");
    }

    @Test
    void maxDrawdown_단조증가면_낙폭0() {
        List<BigDecimal> indices = List.of(
                new BigDecimal("100"), new BigDecimal("110"), new BigDecimal("120"));

        BigDecimal result = ReturnMetrics.maxDrawdown(indices);

        assertThat(result).isEqualByComparingTo("0");
    }

    @Test
    void maxDrawdown_봉우리후하락후회복_정규화지수() {
        // 100 -> 120(peak) -> 90(peak 대비 -25%) -> 110(회복하지만 peak는 아직 120)
        List<BigDecimal> indices = List.of(
                new BigDecimal("100"), new BigDecimal("120"), new BigDecimal("90"), new BigDecimal("110"));

        BigDecimal result = ReturnMetrics.maxDrawdown(indices);

        assertThat(result).isEqualByComparingTo("-0.25");
    }

    @Test
    void maxDrawdown_원시금액스케일도_동일한낙폭비율() {
        // 정규화 지수(100,120,90,110)와 동일 비율의 원시 달러 금액(10000,12000,9000,11000)
        // -> Task 7이 totalAsset을 그대로 넣어도 동일 결과가 나온다는 근거
        List<BigDecimal> rawAmounts = List.of(
                new BigDecimal("10000"), new BigDecimal("12000"),
                new BigDecimal("9000"), new BigDecimal("11000"));

        BigDecimal result = ReturnMetrics.maxDrawdown(rawAmounts);

        assertThat(result).isEqualByComparingTo("-0.25");
    }
}
