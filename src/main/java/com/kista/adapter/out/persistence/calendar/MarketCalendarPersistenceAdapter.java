package com.kista.adapter.out.persistence.calendar;

import com.kista.common.UsTradeDates;
import com.kista.domain.port.out.MarketCalendarPort;
import com.kista.domain.port.out.MarketHolidayStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketCalendarPersistenceAdapter implements MarketCalendarPort, MarketHolidayStorePort {

    private final UsMarketHolidayJpaRepository holidayRepository;

    @Override
    public boolean isMarketOpen(LocalDate date) {
        // date는 KST 거래일 — 휴장일 테이블은 US 달력 기준이므로 변환 필수
        LocalDate usDate = UsTradeDates.toUsTradeDate(date);
        if (usDate.getDayOfWeek() == DayOfWeek.SATURDAY || usDate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return false;
        }
        if (holidayRepository.countByYear(usDate.getYear()) == 0) {
            log.error("{}년 시장 캘린더 데이터 없음 — 폐장으로 폴백 (refreshCalendar 미실행 의심)", usDate.getYear());
            return false;
        }
        return !holidayRepository.existsByTradeDate(usDate);
    }

    @Override
    public long countByYear(int year) {
        return holidayRepository.countByYear(year);
    }

    @Override
    @Transactional
    public void replaceByYear(int year, List<LocalDate> holidays) {
        holidayRepository.deleteByYear(year);
        List<UsMarketHolidayEntity> entities = holidays.stream()
                .map(UsMarketHolidayEntity::of)
                .toList();
        holidayRepository.saveAll(entities);
        log.info("{}년 휴장일 {}건 적재 완료", year, entities.size());
    }

    @Override
    @Transactional
    public void replaceByMonth(int year, int month, List<LocalDate> holidays) {
        holidayRepository.deleteByMonth(year, month);
        List<UsMarketHolidayEntity> entities = holidays.stream()
                .map(UsMarketHolidayEntity::of)
                .toList();
        holidayRepository.saveAll(entities);
        log.info("{}년 {}월 휴장일 {}건 적재 완료", year, month, entities.size());
    }

    @Override
    public List<LocalDate> findHolidaysForMonth(int year, int month) {
        // 해당 월의 첫날~마지막날 범위로 휴장일 조회
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        return holidayRepository.findByTradeDateBetween(start, end)
                .stream().map(UsMarketHolidayEntity::getTradeDate).toList();
    }
}
