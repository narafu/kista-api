package com.kista.domain.port.out;

import java.time.LocalDate;

public interface MarketCalendarPort {
    // 주말은 항상 false; 연도 데이터 미존재 시 false 반환 (안전 폴백 — 매매 skip)
    boolean isMarketOpen(LocalDate date);
}
