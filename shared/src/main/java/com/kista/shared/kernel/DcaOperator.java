package com.kista.shared.kernel;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

// DCA 조건 비교 연산자 — strategy_dca_condition.operator 상수명과 직결(변경 금지)
@Getter
@RequiredArgsConstructor
public enum DcaOperator {
    LTE("이하"),
    LT("미만"),
    GTE("이상"),
    GT("초과");

    private final String label; // 한국어 표시 이름

    // left op right — BigDecimal scale 차이는 compareTo로 무시
    public boolean test(BigDecimal left, BigDecimal right) {
        int cmp = left.compareTo(right);
        return switch (this) {
            case LTE -> cmp <= 0;
            case LT -> cmp < 0;
            case GTE -> cmp >= 0;
            case GT -> cmp > 0;
        };
    }
}
