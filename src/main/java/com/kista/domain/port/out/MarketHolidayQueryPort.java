package com.kista.domain.port.out;

import java.time.LocalDate;
import java.util.List;

public interface MarketHolidayQueryPort {
    List<LocalDate> findHolidaysForMonth(int year, int month);
}
