package com.kista.tradingcore.marketcalendar.application.port.output;

import com.kista.tradingcore.marketcalendar.domain.model.MarketCalendarTradingDay;

import java.util.List;

public interface MarketHolidayStorePort {
    long countByYear(int year);
    List<Integer> findYearsWithHolidays();
    boolean hasCompleteSessionsForYear(int year);
    // 수집 범위의 세션·휴장일을 한 트랜잭션에서 교체 — 멱등 보장
    void replaceSessionsByYear(int year, List<MarketCalendarTradingDay> tradingDays);
    void replaceSessionsByMonth(int year, int month, List<MarketCalendarTradingDay> tradingDays);
}
