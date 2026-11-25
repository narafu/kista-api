package com.kista.api.benchmark.domain.model;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToLongBiFunction;

// 시계열 첫·마지막 유효(값>0) 시점으로 연평균 상승률(CAGR, 비율)을 계산 — kista-ui housingBenchmarkChartFormatters와 동일 식
public final class Cagr {

    // 비율 소수 6자리(UI는 % 소수 1자리 표시)
    public static final int SCALE = 6;

    private Cagr() {}

    // 월 단위(연·월만 사용): (last/first)^(12/개월수) - 1, 개월수 0 이하면 null
    public static <T> @Nullable BigDecimal byMonths(List<T> points, Function<T, LocalDate> date, Function<T, @Nullable BigDecimal> value) {
        return compute(points, date, value, (f, l) -> (l.getYear() * 12 + l.getMonthValue()) - (f.getYear() * 12 + f.getMonthValue()), 12.0);
    }

    // 일 단위: (last/first)^(365.25/일수) - 1, 일수 0 이하면 null
    public static <T> @Nullable BigDecimal byDays(List<T> points, Function<T, LocalDate> date, Function<T, @Nullable BigDecimal> value) {
        return compute(points, date, value, ChronoUnit.DAYS::between, 365.25);
    }

    private static <T> @Nullable BigDecimal compute(List<T> points, Function<T, LocalDate> date,
                                                    Function<T, @Nullable BigDecimal> value,
                                                    ToLongBiFunction<LocalDate, LocalDate> span, double perYear) {
        // 값이 양수인 유효 시점만 추려 첫·끝을 취한다
        List<T> valid = points.stream()
                .filter(p -> value.apply(p) != null && value.apply(p).signum() > 0)
                .toList();
        if (valid.size() < 2) return null;
        T first = valid.getFirst();
        T last = valid.getLast();
        long gap = span.applyAsLong(date.apply(first), date.apply(last));
        if (gap <= 0) return null;
        // 계산은 double(반올림 전), 결과만 BigDecimal로 감싼다
        double ratio = value.apply(last).doubleValue() / value.apply(first).doubleValue();
        double cagr = Math.pow(ratio, perYear / gap) - 1;
        if (!Double.isFinite(cagr)) return null;
        return BigDecimal.valueOf(cagr).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
