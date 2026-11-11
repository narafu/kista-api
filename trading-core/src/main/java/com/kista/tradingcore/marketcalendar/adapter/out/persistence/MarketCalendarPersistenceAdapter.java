package com.kista.tradingcore.marketcalendar.adapter.out.persistence;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.time.UsTradeDates;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketHolidayStorePort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketCalendarTradingDay;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class MarketCalendarPersistenceAdapter implements MarketCalendarPort, MarketHolidayStorePort {

    private final UsMarketHolidayJpaRepository holidayRepository; // 기존 휴장일 저장소
    private final UsMarketSessionJpaRepository sessionRepository; // 수집 완료 날짜별 실제 세션

    @Override
    public Optional<MarketSessionTimes> findSession(LocalDate tradeDateKst) {
        LocalDate usDate = UsTradeDates.toUsTradeDate(tradeDateKst);
        UsMarketSessionEntity session = sessionRepository.findById(usDate)
                .orElseThrow(() -> new IllegalStateException("시장 세션 미적재: " + tradeDateKst));
        Instant openAt = session.getOpenAt();
        Instant closeAt = session.getCloseAt();
        if (!isValidSession(session)) {
            throw new IllegalStateException("잘못된 시장 세션: " + tradeDateKst);
        }
        if (openAt == null) {
            return Optional.empty();
        }
        return Optional.of(new MarketSessionTimes(openAt, closeAt));
    }

    @Override
    public boolean isMarketOpen(LocalDate date) {
        // date는 KST 거래일 — 휴장일 테이블은 US 달력 기준이므로 변환 필수
        return isUsSessionOpen(UsTradeDates.toUsTradeDate(date));
    }

    @Override
    public boolean isUsSessionOpen(LocalDate usDate) {
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
    public LocalDate usSessionDateOf(LocalDate kstTradeDate) {
        return UsTradeDates.toUsTradeDate(kstTradeDate);
    }

    @Override
    public LocalDate kstTradeDateOf(LocalDate usSessionDate) {
        return UsTradeDates.toKstTradeDate(usSessionDate);
    }

    @Override
    public long countByYear(int year) {
        return holidayRepository.countByYear(year);
    }

    @Override
    public List<Integer> findYearsWithHolidays() {
        return holidayRepository.findYearsWithHolidays();
    }

    @Override
    public boolean hasCompleteSessionsForYear(int year) {
        LocalDate start = LocalDate.of(year, 1, 1);
        LocalDate end = LocalDate.of(year, 12, 31);
        List<UsMarketSessionEntity> sessions = sessionRepository.findByTradeDateBetween(start, end);
        return sessions.size() == start.lengthOfYear() && sessions.stream().allMatch(MarketCalendarPersistenceAdapter::isValidSession);
    }

    @Override
    @Transactional
    public void replaceByYear(int year, List<LocalDate> holidays) {
        sessionRepository.deleteByTradeDateBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
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
        LocalDate start = LocalDate.of(year, month, 1);
        sessionRepository.deleteByTradeDateBetween(start, start.withDayOfMonth(start.lengthOfMonth()));
        holidayRepository.deleteByMonth(year, month);
        List<UsMarketHolidayEntity> entities = holidays.stream()
                .map(UsMarketHolidayEntity::of)
                .toList();
        holidayRepository.saveAll(entities);
        log.info("{}년 {}월 휴장일 {}건 적재 완료", year, month, entities.size());
    }

    @Override
    @Transactional
    public void replaceSessionsByYear(int year, List<MarketCalendarTradingDay> tradingDays) {
        LocalDate start = LocalDate.of(year, 1, 1);
        replaceSessions(start, LocalDate.of(year, 12, 31), tradingDays);
    }

    @Override
    @Transactional
    public void replaceSessionsByMonth(int year, int month, List<MarketCalendarTradingDay> tradingDays) {
        LocalDate start = LocalDate.of(year, month, 1);
        replaceSessions(start, start.withDayOfMonth(start.lengthOfMonth()), tradingDays);
    }

    private void replaceSessions(LocalDate start, LocalDate end, List<MarketCalendarTradingDay> tradingDays) {
        if (tradingDays == null || tradingDays.isEmpty()) {
            throw new IllegalStateException("빈 시장 캘린더");
        }
        Map<LocalDate, UsMarketSessionEntity> openSessions = new HashMap<>();
        // 원본 전체를 검증한 뒤에만 기존 저장 데이터를 교체한다
        for (MarketCalendarTradingDay day : tradingDays) {
            if (day == null || day.date() == null || day.open() == null || day.close() == null
                    || day.date().isBefore(start) || day.date().isAfter(end) || isWeekend(day.date())
                    || !day.open().isBefore(day.close()) || openSessions.containsKey(day.date())) {
                throw new IllegalStateException("잘못된 시장 캘린더 항목: " + day);
            }
            LocalDateTime open = day.date().atTime(day.open());
            LocalDateTime close = day.date().atTime(day.close());
            if (TimeZones.NY.getRules().getValidOffsets(open).size() != 1
                    || TimeZones.NY.getRules().getValidOffsets(close).size() != 1) {
                throw new IllegalStateException("유효하지 않은 뉴욕 시각: " + day);
            }
            openSessions.put(day.date(), UsMarketSessionEntity.of(day.date(),
                    open.atZone(TimeZones.NY).toInstant(), close.atZone(TimeZones.NY).toInstant()));
        }

        // 수집된 범위의 모든 날짜를 기록해 휴장일과 미적재를 구분한다
        List<UsMarketSessionEntity> sessions = new ArrayList<>();
        List<UsMarketHolidayEntity> holidays = new ArrayList<>();
        start.datesUntil(end.plusDays(1)).forEach(date -> {
            UsMarketSessionEntity session = openSessions.get(date);
            if (session == null) {
                session = UsMarketSessionEntity.of(date, null, null);
                if (!isWeekend(date)) {
                    holidays.add(UsMarketHolidayEntity.of(date));
                }
            }
            sessions.add(session);
        });

        // 휴장일과 세션은 같은 트랜잭션에서 교체한다
        sessionRepository.deleteByTradeDateBetween(start, end);
        if (start.getMonthValue() == 1 && end.getMonthValue() == 12) {
            holidayRepository.deleteByYear(start.getYear());
        } else {
            holidayRepository.deleteByMonth(start.getYear(), start.getMonthValue());
        }
        holidayRepository.saveAll(holidays);
        sessionRepository.saveAll(sessions);
    }

    private static boolean isWeekend(LocalDate date) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static boolean isValidSession(UsMarketSessionEntity session) {
        Instant open = session.getOpenAt();
        Instant close = session.getCloseAt();
        LocalDate date = session.getTradeDate();
        return open == null && close == null || open != null && close != null && open.isBefore(close)
                && !isWeekend(date) && open.atZone(TimeZones.NY).toLocalDate().equals(date)
                && close.atZone(TimeZones.NY).toLocalDate().equals(date);
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
