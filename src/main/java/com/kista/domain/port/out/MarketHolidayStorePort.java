package com.kista.domain.port.out;

import java.time.LocalDate;
import java.util.List;

public interface MarketHolidayStorePort {
    long countByYear(int year);
    // 해당 연도 기존 데이터 삭제 후 새 휴장일 목록으로 교체 — 멱등 보장
    void replaceByYear(int year, List<LocalDate> holidays);
    // 해당 월 기존 데이터 삭제 후 새 휴장일 목록으로 교체 — 멱등 보장
    void replaceByMonth(int year, int month, List<LocalDate> holidays);
}
