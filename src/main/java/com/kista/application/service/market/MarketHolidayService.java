package com.kista.application.service.market;

import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.port.in.MarketUseCase;
import com.kista.domain.port.out.MarketCalendarPort;
import com.kista.domain.port.out.TossCandlePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
class MarketHolidayService implements MarketUseCase {

    private final MarketCalendarPort marketCalendarPort; // 휴장일 조회 (읽기 전용)
    private final TossCandlePort tossCandlePort;

    @Override
    public List<LocalDate> getMonthlyHolidays(int year, int month) {
        return marketCalendarPort.findHolidaysForMonth(year, month);
    }

    @Override
    public List<TossCandle> getDailyCandles(String symbol, int count) {
        return tossCandlePort.getLatestCandles(symbol, "1d", count);
    }
}
