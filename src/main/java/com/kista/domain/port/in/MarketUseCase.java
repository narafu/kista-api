package com.kista.domain.port.in;

import java.time.LocalDate;
import java.util.List;

// 미국 시장 캘린더 조회
public interface MarketUseCase {
    List<LocalDate> getMonthlyHolidays(int year, int month);
}
