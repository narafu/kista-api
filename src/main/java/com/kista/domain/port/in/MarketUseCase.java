package com.kista.domain.port.in;

import com.kista.domain.model.toss.TossCandle;

import java.time.LocalDate;
import java.util.List;

// 미국 시장 캘린더·시세 조회 (계좌 무관 공용)
public interface MarketUseCase {
    List<LocalDate> getMonthlyHolidays(int year, int month);

    // 일봉만 지원 — count는 1~200으로 clamp
    List<TossCandle> getDailyCandles(String symbol, int count);
}
