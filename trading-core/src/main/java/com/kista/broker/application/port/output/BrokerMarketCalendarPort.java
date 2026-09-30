package com.kista.broker.application.port.output;

import com.kista.broker.domain.model.MarketCalendarDay;

import java.time.LocalDate;
import java.util.List;

// 시장 캘린더 조회 (현재 Toss만 구현) — 공통 API, Account 토큰 불필요. 벤더 중립 MarketCalendarDay 반환
public interface BrokerMarketCalendarPort {
    List<MarketCalendarDay> getMarketCalendar(LocalDate from, LocalDate to);
}
