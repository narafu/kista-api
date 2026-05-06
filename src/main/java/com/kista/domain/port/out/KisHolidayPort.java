package com.kista.domain.port.out;

import java.time.LocalDate;

public interface KisHolidayPort {
    boolean isMarketOpen(LocalDate date);
}
