package com.kista.tradingcore.marketcalendar.adapter.out.persistence;

import com.kista.shared.support.DataJpaTestBase;
import com.kista.tradingcore.marketcalendar.domain.model.MarketCalendarTradingDay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@Import(MarketCalendarPersistenceAdapter.class)
@Execution(ExecutionMode.SAME_THREAD)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MarketCalendarAtomicRefreshTest extends DataJpaTestBase {

    @Autowired MarketCalendarPersistenceAdapter calendarAdapter;
    @MockitoSpyBean UsMarketSessionJpaRepository sessionRepository; // 세션 저장 실패 주입

    @Test
    void failedSessionWriteRollsBackBothTables() {
        int year = 8800;
        LocalDate start = LocalDate.of(year, 8, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        assertThat(sessionRepository.findByTradeDateBetween(start, end)).isEmpty();
        assertThat(calendarAdapter.findHolidaysForMonth(year, 8)).isEmpty();
        LocalDate date = start.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        try {
            calendarAdapter.replaceSessionsByMonth(year, 8, List.of(
                    new MarketCalendarTradingDay(date, LocalTime.of(9, 30), LocalTime.of(13, 0))));
            var originalSession = calendarAdapter.findSession(date.plusDays(1));
            var originalHolidays = calendarAdapter.findHolidaysForMonth(year, 8);

            doThrow(new IllegalStateException("session write failed")).when(sessionRepository).saveAll(anyList());
            assertThatThrownBy(() -> calendarAdapter.replaceSessionsByMonth(year, 8, List.of(
                    new MarketCalendarTradingDay(date.plusDays(1), LocalTime.of(9, 30), LocalTime.of(16, 0)))))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(calendarAdapter.findSession(date.plusDays(1))).isEqualTo(originalSession);
            assertThat(calendarAdapter.findHolidaysForMonth(year, 8)).containsExactlyInAnyOrderElementsOf(originalHolidays);
        } finally {
            reset(sessionRepository);
            calendarAdapter.replaceByMonth(year, 8, List.of());
        }
    }
}