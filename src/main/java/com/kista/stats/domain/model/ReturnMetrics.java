package com.kista.stats.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

// 자산 곡선 성과 지표 순수 계산 유틸 — HousingBenchmarkComparisonBuilder에서 추출, 백테스트(Task 7)와 공용
public final class ReturnMetrics {

    private static final int SCALE = 10;
    private static final RoundingMode HALF_UP = RoundingMode.HALF_UP;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ReturnMetrics() {
        // 인스턴스 없는 정적 유틸
    }

    // value를 initialValue 기준 100으로 정규화한 지수
    public static BigDecimal normalize(BigDecimal value, BigDecimal initialValue) {
        return value.divide(initialValue, SCALE, HALF_UP)
                .multiply(HUNDRED)
                .setScale(SCALE, HALF_UP);
    }

    // 100 기준 정규화된 최종 지수로부터 누적수익률 계산
    public static BigDecimal cumulativeReturn(BigDecimal lastIndex) {
        return lastIndex.divide(HUNDRED, SCALE, HALF_UP)
                .subtract(BigDecimal.ONE)
                .setScale(SCALE, HALF_UP);
    }

    // 100 기준 정규화된 최종 지수와 연간 주기수로부터 연환산수익률 계산
    public static BigDecimal annualizedReturn(BigDecimal lastIndex, double periodsPerYear) {
        if (lastIndex.signum() <= 0) {
            // 지수가 0 이하(전액 손실)면 Math.pow가 NaN을 낼 수 있어 -100%로 확정
            return BigDecimal.ONE.negate().setScale(SCALE, HALF_UP);
        }
        double annualized = Math.pow(
                lastIndex.divide(HUNDRED, SCALE, HALF_UP).doubleValue(),
                periodsPerYear) - 1.0;
        return BigDecimal.valueOf(annualized).setScale(SCALE, HALF_UP);
    }

    // 최대낙폭(peak 대비 최대 하락 비율) — index/peak 비율만 쓰므로 스케일 불변(정규화 지수·원시 금액 모두 가능)
    public static BigDecimal maxDrawdown(List<BigDecimal> indices) {
        BigDecimal peak = null;
        BigDecimal maxDrawdown = BigDecimal.ZERO.setScale(SCALE, HALF_UP);
        for (BigDecimal index : indices) {
            if (peak == null || index.compareTo(peak) > 0) {
                peak = index;
            }
            if (peak.signum() <= 0) {
                continue;
            }
            BigDecimal drawdown = index.divide(peak, SCALE, HALF_UP)
                    .subtract(BigDecimal.ONE)
                    .setScale(SCALE, HALF_UP);
            if (drawdown.compareTo(maxDrawdown) < 0) {
                maxDrawdown = drawdown;
            }
        }
        return maxDrawdown;
    }
}
