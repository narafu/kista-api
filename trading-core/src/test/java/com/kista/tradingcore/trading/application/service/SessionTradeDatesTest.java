package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionTradeDatesTest {

    private final MarketCalendarPort calendar = mock(MarketCalendarPort.class);

    @Test
    void actualCloseDeterminesBoundaryForEarlyDstAndStandardSessions() {
        checkBoundary(LocalDate.of(2026, 11, 28), 3, 0);
        checkBoundary(LocalDate.of(2026, 6, 3), 5, 0);
        checkBoundary(LocalDate.of(2026, 1, 6), 6, 0);
    }

    private void checkBoundary(LocalDate date, int hour, int minute) {
        ZonedDateTime close = date.atTime(hour, minute).atZone(TimeZones.KST);
        when(calendar.findSession(date)).thenReturn(Optional.of(new MarketSessionTimes(close.minusHours(6).toInstant(), close.toInstant())));
        ZonedDateTime boundary = close.minusMinutes(30);
        assertThat(SessionTradeDates.nextTradeDate(boundary.minusNanos(1), calendar)).isEqualTo(date);
        assertThat(SessionTradeDates.tradeDateCandidates(boundary.minusNanos(1), calendar)).containsExactly(date);
        assertThat(SessionTradeDates.nextTradeDate(boundary, calendar)).isEqualTo(date.plusDays(1));
        assertThat(SessionTradeDates.tradeDateCandidates(boundary, calendar)).isEqualTo(List.of(date, date.plusDays(1)));
    }

    @Test
    void holidayUsesExistingBoundaryButMissingCalendarFailsClosed() {
        LocalDate date = LocalDate.of(2026, 1, 6);
        ZonedDateTime time = date.atTime(4, 30).atZone(TimeZones.KST);
        when(calendar.findSession(date)).thenReturn(Optional.empty());
        assertThat(SessionTradeDates.nextTradeDate(time, calendar)).isEqualTo(date.plusDays(1));
        when(calendar.findSession(date)).thenThrow(new IllegalStateException("캘린더가 없습니다."));
        assertThatThrownBy(() -> SessionTradeDates.tradeDateCandidates(time, calendar)).isInstanceOf(IllegalStateException.class);
    }
}