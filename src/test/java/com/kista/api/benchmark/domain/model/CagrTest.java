package com.kista.api.benchmark.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CagrTest {

    private record P(LocalDate d, BigDecimal v) {}

    private static P p(String d, String v) {
        return new P(LocalDate.parse(d), v == null ? null : new BigDecimal(v));
    }

    @Test
    void 월_단위_정상() {
        // 12개월 2배 -> 100%
        var r = Cagr.byMonths(List.of(p("2025-01-01", "100"), p("2026-01-01", "200")), P::d, P::v);
        assertThat(r).isEqualByComparingTo("1.000000");
    }

    @Test
    void 일_단위_정상() {
        // 365일 동안 1.21배 -> 1.21^(365.25/365)-1
        var r = Cagr.byDays(List.of(p("2025-01-01", "100"), p("2026-01-01", "121")), P::d, P::v);
        assertThat(r).isEqualByComparingTo(
                BigDecimal.valueOf(Math.pow(1.21, 365.25 / 365) - 1).setScale(6, RoundingMode.HALF_UP));
    }

    @Test
    void 유효점_하나면_null() {
        // 0 이하·null 값은 유효점에서 제외
        assertThat(Cagr.byDays(List.of(p("2025-01-01", "0"), p("2025-06-01", "100"), p("2026-01-01", null)), P::d, P::v)).isNull();
        assertThat(Cagr.byMonths(List.of(p("2025-01-01", "100")), P::d, P::v)).isNull();
    }

    @Test
    void 기간_0이면_null() {
        assertThat(Cagr.byDays(List.of(p("2025-01-01", "100"), p("2025-01-01", "110")), P::d, P::v)).isNull();
        // 월 단위는 같은 달이면 일이 달라도 0
        assertThat(Cagr.byMonths(List.of(p("2025-01-01", "100"), p("2025-01-31", "110")), P::d, P::v)).isNull();
    }
}
