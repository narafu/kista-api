package com.kista.application.service.market;

import com.kista.domain.port.in.MarketUseCase;
import com.kista.domain.port.out.MarketHolidayQueryPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
class MarketHolidayService implements MarketUseCase {

    private final MarketHolidayQueryPort marketHolidayQueryPort;

    @Override
    public List<LocalDate> getMonthlyHolidays(int year, int month) {
        return marketHolidayQueryPort.findHolidaysForMonth(year, month);
    }
}
