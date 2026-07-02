package com.kista.domain.port.out;

import java.time.LocalDate;
import java.util.List;

// 시장 캘린더 읽기 전용 포트 — 쓰기(갱신)는 MarketHolidayStorePort 분리
public interface MarketCalendarPort {
    // 주말은 항상 false; 연도 데이터 미존재 시 false 반환 (안전 폴백 — 매매 skip)
    boolean isMarketOpen(LocalDate date);

    // 해당 월 휴장일 목록 조회 (UI 캘린더 표시용)
    List<LocalDate> findHolidaysForMonth(int year, int month);
}
