package com.kista.adapter.out.persistence.calendar;

import com.kista.support.DataJpaTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(MarketCalendarPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD) // @DataJpaTest + parallel execution — 트랜잭션 경합 방지
class MarketCalendarPersistenceAdapterTest extends DataJpaTestBase {

    @Autowired MarketCalendarPersistenceAdapter calendarAdapter;

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
}
