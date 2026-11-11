package com.kista.tradingcore.marketcalendar.application.port.output;

import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

// 시장 캘린더 읽기 전용 포트 — 쓰기(갱신)는 MarketHolidayStorePort 분리
public interface MarketCalendarPort {
    // KST 거래일의 실제 세션; 수집된 휴장일은 empty, 미적재 또는 검증 실패는 예외
    Optional<MarketSessionTimes> findSession(LocalDate tradeDateKst);
    // 주말은 항상 false; 연도 데이터 미존재 시 false 반환 (안전 폴백 — 매매 skip)
    boolean isMarketOpen(LocalDate date);

    // 해당 월 휴장일 목록 조회 (UI 캘린더 표시용)
    List<LocalDate> findHolidaysForMonth(int year, int month);

    // KST 거래일 → US 세션일 (KST 아침 정산일 D ↔ US D-1)
    LocalDate usSessionDateOf(LocalDate kstTradeDate);

    // US 세션일 → KST 거래일
    LocalDate kstTradeDateOf(LocalDate usSessionDate);

    // US 세션일 기준 개장 여부 — 주말 false, 연도 데이터 미존재 시 false (isMarketOpen과 같은 데이터)
    boolean isUsSessionOpen(LocalDate usSessionDate);
}
