package com.kista.domain.port.out;

import com.kista.domain.model.account.Account;
import com.kista.domain.model.toss.TossMarketSession;

import java.time.LocalDate;
import java.util.List;

public interface TossMarketCalendarPort {
    // GET /api/v1/market-calendar/US?from={from}&to={to}
    List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to, Account account);
}
