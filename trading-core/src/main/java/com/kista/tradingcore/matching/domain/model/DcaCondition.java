package com.kista.tradingcore.matching.domain.model;

import com.kista.shared.kernel.DcaIndicator;
import com.kista.shared.kernel.DcaOperator;

import java.math.BigDecimal;

// DCA 회차 조건 1개 — strategy_dca_condition 1행. 검증 실패는 IAE(400)
public record DcaCondition(
        DcaIndicator indicator, // 지표
        DcaOperator operator,   // 비교 연산자
        Integer smaPeriod,      // SMA 전용 기간
        BigDecimal threshold    // AVG_PRICE: n% / RSI·FEAR_GREED: 0~100 / SMA: null
) {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public DcaCondition {
        if (indicator == null || operator == null) throw new IllegalArgumentException("조건의 지표와 연산자는 필수입니다.");
        if (indicator == DcaIndicator.SMA) {
            if (smaPeriod == null || !DcaIndicator.SMA_PERIODS.contains(smaPeriod)) {
                throw new IllegalArgumentException("이동평균 기간은 " + DcaIndicator.SMA_PERIODS + " 중 하나여야 합니다.");
            }
            if (threshold != null) throw new IllegalArgumentException("이동평균 조건에는 기준값을 지정할 수 없습니다.");
        } else {
            if (smaPeriod != null) throw new IllegalArgumentException("이동평균 기간은 이동평균 조건에서만 지정할 수 있습니다.");
            if (threshold == null) throw new IllegalArgumentException("조건 기준값이 필요합니다.");
            // numeric(10,2) — 소수 2자리, 정수부 8자리 이하
            BigDecimal t = threshold.stripTrailingZeros();
            if (t.scale() > 2 || t.precision() - t.scale() > 8) throw new IllegalArgumentException("조건 기준값 형식이 올바르지 않습니다.");
            boolean percentScale = indicator == DcaIndicator.RSI || indicator == DcaIndicator.FEAR_GREED;
            if (percentScale && (threshold.signum() < 0 || threshold.compareTo(HUNDRED) > 0)) {
                throw new IllegalArgumentException("기준값은 0~100 사이여야 합니다.");
            }
            if (indicator == DcaIndicator.AVG_PRICE && threshold.compareTo(HUNDRED.negate()) <= 0) {
                throw new IllegalArgumentException("평단가 조건 기준값은 -100%보다 커야 합니다.");
            }
        }
    }
}
