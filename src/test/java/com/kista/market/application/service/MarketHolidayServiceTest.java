package com.kista.market.application.service;

import com.kista.market.application.port.output.CandleQueryPort;
import com.kista.market.application.port.output.MarketCalendarQueryPort;
import com.kista.market.domain.model.TossDailyCandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarketHolidayServiceTest {

    @Mock MarketCalendarQueryPort marketCalendarQueryPort;
    @Mock CandleQueryPort candleQueryPort;

    @Test
    void getMonthlyHolidays_포트에_그대로_위임한다() {
        MarketHolidayService target = new MarketHolidayService(marketCalendarQueryPort, candleQueryPort);
        when(marketCalendarQueryPort.findHolidaysForMonth(2026, 1))
                .thenReturn(List.of(LocalDate.of(2026, 1, 1)));

        List<LocalDate> result = target.getMonthlyHolidays(2026, 1);

        assertThat(result).containsExactly(LocalDate.of(2026, 1, 1));
        verify(marketCalendarQueryPort).findHolidaysForMonth(2026, 1);
    }

    @Test
    void getDailyCandles_포트에_그대로_위임한다() {
        MarketHolidayService target = new MarketHolidayService(marketCalendarQueryPort, candleQueryPort);
        TossDailyCandle candle = new TossDailyCandle(LocalDate.of(2026, 1, 2), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L);
        when(candleQueryPort.latestDailyCandles("QQQ", 200)).thenReturn(List.of(candle));

        List<TossDailyCandle> result = target.getDailyCandles("QQQ", 200);

        assertThat(result).containsExactly(candle);
        verify(candleQueryPort).latestDailyCandles("QQQ", 200);
    }
}
