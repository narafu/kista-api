package com.kista.market.application.port.output;

import com.kista.contract.marketcalendar.MarketSessionResponse;

import java.time.LocalDate;
import java.util.List;

// marketcalendar 모듈 내부API(MarketCalendarInternalController) 호출 포트 — market이
// marketcalendar.MarketCalendarPort/MarketSessionSnapshot을 직접 참조하지 않기 위함
public interface MarketCalendarQueryPort {
    List<LocalDate> findHolidaysForMonth(int year, int month);
    MarketSessionResponse currentSession();
}
