package com.kista.api.finance.domain.model;

import com.kista.api.finance.domain.model.FlowPeriod.DateRange;
import com.kista.api.finance.domain.model.FlowPeriod.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FlowPeriod 단위 테스트")
class FlowPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 23);

    private FlowPeriod yearly(String month) {
        return new FlowPeriod(YearMonth.parse(month), Mode.YEARLY);
    }

    @Test
    @DisplayName("[period.test] periodRange 연간: 올해면 1월 1일~오늘(YTD)")
    void range_yearly_currentYear() {
        assertThat(yearly("2026-03").range(TODAY)).isEqualTo(new DateRange(LocalDate.of(2026, 1, 1), TODAY));
    }

    @Test
    @DisplayName("[period.test] periodRange 연간: 과거 연도면 mm과 무관하게 1~12월 전체")
    void range_yearly_pastYear() {
        assertThat(yearly("2025-03").range(TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)));
    }

    @Test
    @DisplayName("[period.test] periodRange 월간: 그 달 1일~말일")
    void range_monthly() {
        assertThat(new FlowPeriod(YearMonth.of(2024, 2), Mode.MONTHLY).range(TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)));
    }

    @Test
    @DisplayName("[period.test] elapsedMonthsInYear: 올해면 오늘 월, 과거 연도면 12")
    void elapsedMonths() {
        assertThat(yearly("2026-03").elapsedMonthsInYear(TODAY)).isEqualTo(8);
        assertThat(yearly("2025-03").elapsedMonthsInYear(TODAY)).isEqualTo(12);
    }

    @Test
    @DisplayName("[period.test] previousYearRange: 올해면 전년 1월~오늘과 같은 일자")
    void previousYearRange_currentYear() {
        assertThat(yearly("2026-08").previousYearRange(TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 8, 23)));
    }

    @Test
    @DisplayName("[period.test] previousYearRange: 전년에 없는 날짜(윤년 2/29)는 그 달 말일로 clamp")
    void previousYearRange_leapDayClamped() {
        assertThat(yearly("2024-02").previousYearRange(LocalDate.of(2024, 2, 29)))
                .isEqualTo(new DateRange(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 2, 28)));
    }

    @Test
    @DisplayName("[period.test] previousYearRange: 과거 연도면 전년 전체")
    void previousYearRange_pastYear() {
        assertThat(yearly("2025-03").previousYearRange(TODAY))
                .isEqualTo(new DateRange(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)));
    }

    @Test
    @DisplayName("[flowAggregate.test] trendBuckets: 월간은 선택월 포함 limit개월, 연간 올해 버킷은 오늘까지")
    void buckets() {
        var monthly = new FlowPeriod(YearMonth.of(2026, 8), Mode.MONTHLY).buckets(TODAY, 2);
        assertThat(monthly).extracting(FlowPeriod.Bucket::label).containsExactly("2026-07", "2026-08");
        assertThat(monthly.getFirst().range()).isEqualTo(new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31)));

        var yearly = yearly("2026-08").buckets(TODAY, 2);
        assertThat(yearly).extracting(FlowPeriod.Bucket::label).containsExactly("2025", "2026");
        assertThat(yearly.get(0).range().to()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(yearly.get(1).range().to()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("[flowAggregate.test] 'all' 버킷 수: 가장 오래된 거래 월/연도부터, 월간 30개월·연간 3개년")
    void bucketCountSince() {
        LocalDate earliest = LocalDate.of(2024, 3, 1);
        assertThat(new FlowPeriod(YearMonth.of(2026, 8), Mode.MONTHLY).bucketCountSince(earliest)).isEqualTo(30);
        assertThat(yearly("2026-08").bucketCountSince(earliest)).isEqualTo(3);
    }

    @Test
    @DisplayName("전체 조회 버킷 수는 극단 날짜에서도 넘치지 않고 상한으로 잘린다")
    void bucketCountSince_capped() {
        FlowPeriod monthly = new FlowPeriod(YearMonth.of(2026, 8), Mode.MONTHLY);
        assertThat(monthly.bucketCountSince(LocalDate.of(1, 1, 1))).isEqualTo(FlowPeriod.MAX_ALL_BUCKETS);
        assertThat(new FlowPeriod(YearMonth.of(999_999_999, 12), Mode.MONTHLY).bucketCountSince(LocalDate.MIN))
                .isEqualTo(FlowPeriod.MAX_ALL_BUCKETS);
    }
}
