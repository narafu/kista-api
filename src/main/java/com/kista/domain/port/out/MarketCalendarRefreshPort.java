package com.kista.domain.port.out;

public interface MarketCalendarRefreshPort {
    // Alpaca Calendar API에서 해당 연도 휴장일 목록을 조회해 DB에 적재
    void refreshCalendar(int year);
}
