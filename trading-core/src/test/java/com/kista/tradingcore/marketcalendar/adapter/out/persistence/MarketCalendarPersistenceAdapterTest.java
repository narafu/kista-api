package com.kista.tradingcore.marketcalendar.adapter.out.persistence;

import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.marketcalendar.domain.model.MarketCalendarTradingDay;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(MarketCalendarPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class MarketCalendarPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired MarketCalendarPersistenceAdapter calendarAdapter;
    @Autowired UsMarketSessionJpaRepository sessionRepository;

    @Test
    void findHolidaysForMonth_returnsOnlyHolidaysWithinMonthBoundary() {
        // 7월 경계: 6월 마지막날·8월 첫날은 제외되어야 함
        List<LocalDate> julyHolidays = List.of(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 24));
        calendarAdapter.replaceByMonth(2026, 7, julyHolidays);
        calendarAdapter.replaceByMonth(2026, 6, List.of(LocalDate.of(2026, 6, 30)));
        calendarAdapter.replaceByMonth(2026, 8, List.of(LocalDate.of(2026, 8, 1)));

        List<LocalDate> found = calendarAdapter.findHolidaysForMonth(2026, 7);

        assertThat(found)
                .containsExactlyInAnyOrderElementsOf(julyHolidays)
                .doesNotContain(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 8, 1));
    }

    @Test
    void replaceByMonth_calledTwice_replacesRatherThanAccumulates() {
        calendarAdapter.replaceByMonth(2026, 7, List.of(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 24)));

        // 같은 달을 다른 목록으로 재적재 — 기존 행은 삭제되고 새 목록으로 교체되어야 함
        calendarAdapter.replaceByMonth(2026, 7, List.of(LocalDate.of(2026, 7, 4)));

        List<LocalDate> found = calendarAdapter.findHolidaysForMonth(2026, 7);

        assertThat(found).containsExactly(LocalDate.of(2026, 7, 4));
    }

    @Test
    void replaceByYear_calledTwice_replacesRatherThanAccumulates() {
        calendarAdapter.replaceByYear(2026, List.of(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 25)));

        calendarAdapter.replaceByYear(2026, List.of(LocalDate.of(2026, 7, 3)));

        assertThat(calendarAdapter.countByYear(2026)).isEqualTo(1);
        assertThat(calendarAdapter.findHolidaysForMonth(2026, 7)).containsExactly(LocalDate.of(2026, 7, 3));
        assertThat(calendarAdapter.findHolidaysForMonth(2026, 1)).isEmpty();
    }

    @Test
    void isMarketOpen_holidayUtcDate_returnsFalse() {
        // isMarketOpen(date)는 KST 도메인 날짜를 -1일 하여 UTC(=US 거래일) 기준으로 조회
        // KST 2026-07-04 → UTC 2026-07-03 을 휴장일로 등록
        calendarAdapter.replaceByYear(2026, List.of(LocalDate.of(2026, 7, 3)));

        assertThat(calendarAdapter.isMarketOpen(LocalDate.of(2026, 7, 4))).isFalse();
    }

    @Test
    void isMarketOpen_weekday_notHoliday_returnsTrue() {
        // 2026-07-01(수, KST) → UTC 2026-06-30(화), 평일이며 휴장일 아님
        calendarAdapter.replaceByYear(2026, List.of(LocalDate.of(2026, 7, 3)));

        assertThat(calendarAdapter.isMarketOpen(LocalDate.of(2026, 7, 1))).isTrue();
    }

    @Test
    void isMarketOpen_weekend_returnsFalse() {
        // 2026-07-05(일, KST) → UTC 2026-07-04(토) — 주말은 캘린더 데이터 유무와 무관하게 폐장
        calendarAdapter.replaceByYear(2026, List.of(LocalDate.of(2026, 7, 3)));

        assertThat(calendarAdapter.isMarketOpen(LocalDate.of(2026, 7, 5))).isFalse();
    }

    @Test
    void isMarketOpen_noCalendarDataForYear_fallsBackToClosed() {
        // 해당 연도 데이터가 전혀 없으면 안전 폴백으로 폐장 처리
        assertThat(calendarAdapter.isMarketOpen(LocalDate.of(2099, 7, 1))).isFalse();
    }

    @Test
    void findSession_usesNewYorkDstAndActualEarlyClose() {
        calendarAdapter.replaceSessionsByMonth(2026, 3, List.of(
                day("2026-03-06", "09:30", "16:00"),
                day("2026-03-09", "09:30", "16:00")));
        calendarAdapter.replaceSessionsByMonth(2026, 11, List.of(
                day("2026-11-27", "09:30", "13:00")));

        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-03-07"))).contains(
                times("2026-03-06T14:30:00Z", "2026-03-06T21:00:00Z"));
        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-03-10"))).contains(
                times("2026-03-09T13:30:00Z", "2026-03-09T20:00:00Z"));
        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-11-28"))).contains(
                times("2026-11-27T14:30:00Z", "2026-11-27T18:00:00Z"));
        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-11-27"))).isEmpty();
        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-11-29"))).isEmpty();
        assertThatThrownBy(() -> calendarAdapter.findSession(LocalDate.parse("2026-12-02"))) // 미국 12-01 = 12월 미적재
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findSession_convertsKstAcrossYearBoundary() {
        calendarAdapter.replaceSessionsByMonth(2026, 12, List.of(day("2026-12-31", "09:30", "16:00")));
        assertThat(calendarAdapter.findSession(LocalDate.parse("2027-01-01"))).contains(
                times("2026-12-31T14:30:00Z", "2026-12-31T21:00:00Z"));
        assertThatThrownBy(() -> calendarAdapter.findSession(LocalDate.parse("2027-01-02")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refreshesAreCompleteAndHolidayOnlyChangesInvalidateSessions() {
        calendarAdapter.replaceSessionsByYear(2026, List.of(day("2026-07-02", "09:30", "16:00")));
        assertThat(calendarAdapter.hasCompleteSessionsForYear(2026)).isTrue();
        assertThat(calendarAdapter.findYearsWithHolidays()).contains(2026);
        assertThat(calendarAdapter.findHolidaysForMonth(2026, 7)).contains(LocalDate.parse("2026-07-03"));
        calendarAdapter.replaceSessionsByMonth(2026, 7, List.of(day("2026-07-03", "09:30", "13:00")));
        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-07-03"))).isEmpty();
        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-07-04"))).contains(
                times("2026-07-03T13:30:00Z", "2026-07-03T17:00:00Z"));
        assertThat(calendarAdapter.findHolidaysForMonth(2026, 7)).doesNotContain(LocalDate.parse("2026-07-03"));
        assertThat(calendarAdapter.hasCompleteSessionsForYear(2026)).isTrue();

        calendarAdapter.replaceByMonth(2026, 7, List.of(LocalDate.parse("2026-07-03")));
        assertThat(calendarAdapter.hasCompleteSessionsForYear(2026)).isFalse();
        assertThatThrownBy(() -> calendarAdapter.findSession(LocalDate.parse("2026-07-04")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-08-02"))).isEmpty();
    }

    @Test
    void invalidRefreshIsRejectedBeforeExistingDataIsChanged() {
        calendarAdapter.replaceSessionsByMonth(2026, 11, List.of(day("2026-11-27", "09:30", "13:00")));
        assertThatThrownBy(() -> calendarAdapter.replaceSessionsByMonth(2026, 11, List.of(
                day("2026-11-27", "09:30", "13:00"), day("2026-12-01", "09:30", "16:00"))))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calendarAdapter.findSession(LocalDate.parse("2026-11-28"))).contains(
                times("2026-11-27T14:30:00Z", "2026-11-27T18:00:00Z"));
        assertThat(calendarAdapter.findHolidaysForMonth(2026, 11)).contains(LocalDate.parse("2026-11-26"));
    }

    @Test
    void corruptedStoredSessionFailsClosedAndTriggersBackfill() {
        calendarAdapter.replaceSessionsByYear(2026, List.of(day("2026-03-06", "09:30", "16:00")));
        LocalDate saturday = LocalDate.parse("2026-03-07");
        sessionRepository.saveAndFlush(UsMarketSessionEntity.of(saturday,
                Instant.parse("2026-03-07T14:30:00Z"), Instant.parse("2026-03-07T21:00:00Z")));

        assertThat(calendarAdapter.hasCompleteSessionsForYear(2026)).isFalse();
        assertThatThrownBy(() -> calendarAdapter.findSession(LocalDate.parse("2026-03-08")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MarketCalendarTradingDay day(String date, String open, String close) {
        return new MarketCalendarTradingDay(LocalDate.parse(date), LocalTime.parse(open), LocalTime.parse(close));
    }

    private static MarketSessionTimes times(String open, String close) {
        return new MarketSessionTimes(Instant.parse(open), Instant.parse(close));
    }
}
