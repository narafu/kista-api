package com.kista.api.finance.domain.model;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

// 수입·소비·저축 집계 기간 — 월간은 그 달 전체, 연간은 그 해 1/1~(올해면 오늘, 과거 연도면 12/31).
// 연간 모드의 month는 연도만 의미한다(월은 무시).
public record FlowPeriod(YearMonth month, Mode mode) {

    public enum Mode {MONTHLY, YEARLY}

    public record DateRange(LocalDate from, LocalDate to) {
        public boolean contains(LocalDate date) {
            return !date.isBefore(from) && !date.isAfter(to);
        }
    }

    // 추이 버킷 하나 — label은 월간 "yyyy-MM", 연간 "yyyy"
    public record Bucket(String label, DateRange range) {}

    public DateRange range(LocalDate today) {
        if (mode == Mode.MONTHLY) {
            return new DateRange(month.atDay(1), month.atEndOfMonth());
        }
        int year = month.getYear();
        LocalDate to = year == today.getYear() ? today : LocalDate.of(year, 12, 31);
        return new DateRange(LocalDate.of(year, 1, 1), to);
    }

    // 전년 비교 범위 — 올해는 전년 1/1~전년 같은 월일(없는 날짜면 그 달 말일), 과거 연도는 전년 전체
    public DateRange previousYearRange(LocalDate today) {
        int year = month.getYear();
        int prevYear = year - 1;
        LocalDate from = LocalDate.of(prevYear, 1, 1);
        if (year != today.getYear()) {
            return new DateRange(from, LocalDate.of(prevYear, 12, 31));
        }
        YearMonth prevMonth = YearMonth.of(prevYear, today.getMonth());
        // 윤년 2/29 등 전년에 없는 일자는 말일로 맞춘다
        int day = Math.min(today.getDayOfMonth(), prevMonth.lengthOfMonth());
        return new DateRange(from, prevMonth.atDay(day));
    }

    // 연 평균 분모 — 올해(기준일의 해)면 기준일의 월, 아니면 12
    public int elapsedMonthsInYear(LocalDate today) {
        return month.getYear() == today.getYear() ? today.getMonthValue() : 12;
    }

    // 최근 limit개 버킷(오래된 순) — 월간은 선택월에서 limit-1개월 전까지, 연간은 선택 연도에서 limit-1년 전까지
    public List<Bucket> buckets(LocalDate today, int limit) {
        List<Bucket> buckets = new ArrayList<>();
        for (int i = limit - 1; i >= 0; i--) {
            if (mode == Mode.MONTHLY) {
                YearMonth m = month.minusMonths(i);
                buckets.add(new Bucket(m.toString(), new FlowPeriod(m, Mode.MONTHLY).range(today)));
            } else {
                int year = month.getYear() - i;
                // 버킷 끝은 range()와 같은 규칙이라 요약 합계와 추이 마지막 점이 같은 값을 낸다
                DateRange range = new FlowPeriod(YearMonth.of(year, 1), Mode.YEARLY).range(today);
                buckets.add(new Bucket(String.valueOf(year), range));
            }
        }
        return buckets;
    }

    // 가장 오래된 거래일이 속한 월/연도부터 선택 월/연도까지의 버킷 수(최소 1)
    public int bucketCountSince(LocalDate earliest) {
        int years = month.getYear() - earliest.getYear();
        int span = mode == Mode.YEARLY ? years + 1 : years * 12 + (month.getMonthValue() - earliest.getMonthValue()) + 1;
        return Math.max(1, span);
    }
}
