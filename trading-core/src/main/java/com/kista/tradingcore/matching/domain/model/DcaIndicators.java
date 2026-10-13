package com.kista.tradingcore.matching.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

// DCA 지표 순수 계산 — 결과는 소수 둘째 자리 HALF_UP (detail 표시값과 판정값 일치)
public final class DcaIndicators {

    public static final int RSI_PERIOD = 14; // RSI 기간 고정 (spec 1장 RSI(14))

    private DcaIndicators() {}

    // 마지막 period개 종가 평균 — 부족하면 empty
    public static Optional<BigDecimal> sma(List<BigDecimal> closes, int period) {
        if (closes.size() < period) return Optional.empty();
        BigDecimal sum = closes.subList(closes.size() - period, closes.size()).stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP));
    }

    // Wilder 평활 RSI — 첫 평균은 처음 period개 변화량의 단순평균, 이후 (이전×(period−1)+현재)/period
    public static Optional<BigDecimal> rsiWilder(List<BigDecimal> closes, int period) {
        if (closes.size() < period + 1) return Optional.empty();
        double avgGain = 0;
        double avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = closes.get(i).subtract(closes.get(i - 1)).doubleValue();
            if (change > 0) avgGain += change; else avgLoss -= change;
        }
        avgGain /= period;
        avgLoss /= period;
        for (int i = period + 1; i < closes.size(); i++) {
            double change = closes.get(i).subtract(closes.get(i - 1)).doubleValue();
            avgGain = (avgGain * (period - 1) + Math.max(change, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-change, 0)) / period;
        }
        // 변화 없음 → 50, 하락 없음 → 100 (0 나눗셈 방지)
        double rsi = avgLoss == 0 ? (avgGain == 0 ? 50 : 100) : 100 - 100 / (1 + avgGain / avgLoss);
        return Optional.of(BigDecimal.valueOf(rsi).setScale(2, RoundingMode.HALF_UP));
    }
}
