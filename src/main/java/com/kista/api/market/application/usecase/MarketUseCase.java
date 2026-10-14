package com.kista.api.market.application.usecase;

import com.kista.shared.contract.broker.DailyCandleResponse;

import java.time.LocalDate;
import java.util.List;

// 미국 시장 캘린더·시세 조회 (계좌 무관 공용)
public interface MarketUseCase {
    List<LocalDate> getMonthlyHolidays(int year, int month);

    // 일봉만 지원 — count는 1~200으로 clamp
    List<DailyCandleResponse> getDailyCandles(String symbol, int count);

    // from~to(양끝 포함) 일봉 — 200봉 한도 없음
    List<DailyCandleResponse> getDailyCandles(String symbol, LocalDate from, LocalDate to);
}
