package com.kista.domain.port.in;

import java.time.LocalDate;
import java.util.List;

public interface GetMarketHolidaysUseCase {
    List<LocalDate> getMonthlyHolidays(int year, int month);
}
