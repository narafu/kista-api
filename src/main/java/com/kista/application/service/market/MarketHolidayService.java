package com.kista.application.service.market;

import com.kista.domain.model.toss.TossCandle;
import com.kista.domain.port.in.MarketUseCase;
import com.kista.domain.port.out.MarketHolidayQueryPort;
import com.kista.domain.port.out.TosCandlePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
class MarketHolidayService implements MarketUseCase {

    private final MarketHolidayQueryPort marketHolidayQueryPort;
    private final TosCandlePort tosCandlePort;

    @Override
    public List<LocalDate> getMonthlyHolidays(int year, int month) {
        return marketHolidayQueryPort.findHolidaysForMonth(year, month);
    }

    @Override
    public List<TossCandle> getDailyCandles(String symbol, int count) {
        return tosCandlePort.getLatestCandles(symbol, "1d", count);
    }
}
