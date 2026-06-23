package com.kista.domain.port.out.broker;

import com.kista.domain.model.toss.TossMarketSession;

import java.time.LocalDate;
import java.util.List;

// 시장 캘린더 조회 (Toss 전용) — 공통 API, Account 토큰 불필요
public interface MarketCalendarPort {
    List<TossMarketSession> getMarketCalendar(LocalDate from, LocalDate to);
}
