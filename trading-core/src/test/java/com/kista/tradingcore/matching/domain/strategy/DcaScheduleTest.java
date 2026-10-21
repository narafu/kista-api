package com.kista.tradingcore.matching.domain.strategy;

import com.kista.shared.kernel.DcaFrequency;
import com.kista.shared.kernel.OrderDirection;
import com.kista.shared.kernel.OrderType;
import com.kista.shared.kernel.DcaShortfallPolicy;
import com.kista.shared.kernel.DcaSizingType;
import com.kista.tradingcore.matching.domain.model.DcaRule;
import com.kista.tradingcore.matching.domain.model.DcaSizing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

class DcaScheduleTest {

    // 주말 + 주입 휴장일 제외
    private static Predicate<LocalDate> openExcept(LocalDate... holidays) {
        Set<LocalDate> closed = Set.of(holidays);
        return d -> d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !closed.contains(d);
    }

    private static DcaRule rule(OrderType type, DcaFrequency frequency, DayOfWeek dow, Integer dayOfMonth) {
        return new DcaRule("k", OrderDirection.BUY, frequency, dow, dayOfMonth, type, type == OrderType.MARKET ? 9 : null,
                new DcaSizing(DcaSizingType.AMOUNT, new BigDecimal("100"), null), DcaShortfallPolicy.PARTIAL, List.of());
    }

    private static DcaRule weekly(DayOfWeek dow) {
        return rule(OrderType.MOC, DcaFrequency.WEEKLY, dow, null);
    }

    private static DcaRule monthly(Integer day) {
        return rule(OrderType.MOC, DcaFrequency.MONTHLY, null, day);
    }

    @Test
    void daily_runs_every_open_session() {
        DcaRule daily = rule(OrderType.MOC, DcaFrequency.DAILY, null, null);
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 10, 13), daily, openExcept())).isTrue();
    }

    @Test
    void weekly_holiday_carries_forward_within_week() {
        // 2026-11-23(월)~27(금): 목요일 11-26 추수감사절 휴장 → 목 지정은 금 11-27
        Predicate<LocalDate> open = openExcept(LocalDate.of(2026, 11, 26));
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 11, 27), weekly(DayOfWeek.THURSDAY), open)).isTrue();
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 11, 25), weekly(DayOfWeek.THURSDAY), open)).isFalse();
    }

    @Test
    void weekly_friday_holiday_pulls_back_to_last_open_day() {
        // 2026-04-03(금) Good Friday 휴장 → 금 지정은 목 04-02
        Predicate<LocalDate> open = openExcept(LocalDate.of(2026, 4, 3));
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 4, 2), weekly(DayOfWeek.FRIDAY), open)).isTrue();
    }

    @Test
    void weekly_fully_closed_week_has_no_execution() {
        Predicate<LocalDate> open = openExcept(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13),
                LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16));
        assertThat(DcaSchedule.nextExecutionDay(LocalDate.of(2026, 10, 12), weekly(DayOfWeek.WEDNESDAY), open))
                .contains(LocalDate.of(2026, 10, 21));
    }

    @Test
    void monthly_31_in_30_day_month_uses_last_day() {
        // 2026-09-30(수) 개장 → 31일 지정의 9월 실행일
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 9, 30), monthly(31), openExcept())).isTrue();
    }

    @Test
    void monthly_end_of_month_on_weekend_pulls_back() {
        // 2026-10-31은 토요일 → 말일(null) 지정은 10-30(금)
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 10, 30), monthly(null), openExcept())).isTrue();
        assertThat(DcaSchedule.nextExecutionDay(LocalDate.of(2026, 10, 1), monthly(null), openExcept()))
                .contains(LocalDate.of(2026, 10, 30));
    }

    @Test
    void monthly_target_on_weekend_carries_forward_within_month() {
        // 2026-08-15는 토요일 → 15일 지정은 08-17(월)
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 8, 17), monthly(15), openExcept())).isTrue();
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 8, 14), monthly(15), openExcept())).isFalse();
    }

    @Test
    void closed_day_is_never_execution_day() {
        assertThat(DcaSchedule.isExecutionDay(LocalDate.of(2026, 10, 31), monthly(null), openExcept())).isFalse();
    }

    @Test
    void next_returns_empty_when_calendar_unknown() {
        assertThat(DcaSchedule.nextExecutionDay(LocalDate.of(2026, 10, 1), monthly(1), d -> false)).isEmpty();
    }

    @Test
    void nextExecutionDay_probesEachDateAtMostOnce() {
        java.util.Map<LocalDate, Integer> calls = new java.util.HashMap<>();
        Predicate<LocalDate> open = d -> {
            calls.merge(d, 1, Integer::sum);
            return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
        };

        assertThat(DcaSchedule.nextExecutionDay(LocalDate.of(2026, 10, 14), monthly(null), open))
                .contains(LocalDate.of(2026, 10, 30));
        assertThat(calls.values()).allMatch(n -> n == 1);
    }

    @Test
    void nextExecutionDay_stopsEarlyWhenCalendarMissing() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Predicate<LocalDate> neverOpen = d -> {
            calls.incrementAndGet();
            return false;
        };

        assertThat(DcaSchedule.nextExecutionDay(LocalDate.of(2027, 1, 4), monthly(null), neverOpen)).isEmpty();
        assertThat(calls.get()).isLessThanOrEqualTo(20);
    }
}
