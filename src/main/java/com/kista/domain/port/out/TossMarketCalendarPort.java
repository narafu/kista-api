package com.kista.domain.port.out;

import com.kista.domain.model.toss.TossMarketSession;

import java.time.LocalDate;
import java.util.List;

// 공통 API — 개별 계좌 토큰 불필요, 관리자 자격증명으로 조회
public interface TossMarketCalendarPort {
    // GET /api/v1/market-calendar/US?from={from}&to={to}
    List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to);
}
