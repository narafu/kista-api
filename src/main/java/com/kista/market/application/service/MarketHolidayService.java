package com.kista.market.application.service;

import com.kista.market.application.port.output.CandleQueryPort;
import com.kista.market.application.port.output.MarketCalendarQueryPort;
import com.kista.market.application.usecase.MarketUseCase;
import com.kista.market.domain.model.TossDailyCandle;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
class MarketHolidayService implements MarketUseCase {

    private final MarketCalendarQueryPort marketCalendarQueryPort; // 휴장일 조회 (marketcalendar 내부API)
    private final CandleQueryPort candleQueryPort;                 // Toss 캔들 조회 (broker 내부API)

    @Override
    public List<LocalDate> getMonthlyHolidays(int year, int month) {
        return marketCalendarQueryPort.findHolidaysForMonth(year, month);
    }

    @Override
    public List<TossDailyCandle> getDailyCandles(String symbol, int count) {
        return candleQueryPort.latestDailyCandles(symbol, count);
    }
}
