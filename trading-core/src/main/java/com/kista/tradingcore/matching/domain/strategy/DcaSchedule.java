package com.kista.tradingcore.matching.domain.strategy;

import com.kista.tradingcore.matching.domain.model.DcaRule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

// DCA 실행일 판정 — US 세션일 기준 순수 계산. KST↔US 변환은 호출부(MarketCalendarPort)가 담당
public final class DcaSchedule {

    private static final int SEARCH_LIMIT_DAYS = 400; // 다음 실행일 탐색 상한
    private static final int MAX_CLOSED_STREAK = 14;  // 연속 폐장이 이만큼이면 캘린더 데이터 미존재로 보고 탐색 중단 (실제 연휴는 이보다 짧다)

    private DcaSchedule() {}

    // usSession이 이 규칙의 실행일인지 — 휴장일은 항상 false
    public static boolean isExecutionDay(LocalDate usSession, DcaRule config, Predicate<LocalDate> isOpen) {
        return check(usSession, config, memoize(isOpen));
    }

    // 날짜별 1회만 조회 — 캘린더 판정이 DB 쿼리라 탐색 중 재조회를 막는다
    private static Predicate<LocalDate> memoize(Predicate<LocalDate> isOpen) {
        Map<LocalDate, Boolean> cache = new HashMap<>();
        return d -> cache.computeIfAbsent(d, isOpen::test);
    }

    private static boolean check(LocalDate usSession, DcaRule config, Predicate<LocalDate> isOpen) {
        if (!isOpen.test(usSession)) return false;
        return switch (config.frequency()) {
            case DAILY -> true;
            case WEEKLY -> {
                LocalDate start = usSession.with(DayOfWeek.MONDAY);
                yield usSession.equals(resolve(start.with(config.dayOfWeek()), start, start.plusDays(6), isOpen));
            }
            case MONTHLY -> {
                LocalDate start = usSession.withDayOfMonth(1);
                LocalDate end = usSession.with(TemporalAdjusters.lastDayOfMonth());
                int day = config.dayOfMonth() == null ? end.getDayOfMonth() : Math.min(config.dayOfMonth(), end.getDayOfMonth());
                yield usSession.equals(resolve(start.withDayOfMonth(day), start, end, isOpen));
            }
        };
    }

    // fromUsSession(포함) 이후 첫 실행일 — 상한 내 없으면 empty
    public static Optional<LocalDate> nextExecutionDay(LocalDate fromUsSession, DcaRule config, Predicate<LocalDate> isOpen) {
        Predicate<LocalDate> memo = memoize(isOpen);
        int closedStreak = 0;
        for (int i = 0; i < SEARCH_LIMIT_DAYS; i++) {
            LocalDate d = fromUsSession.plusDays(i);
            closedStreak = memo.test(d) ? 0 : closedStreak + 1;
            if (closedStreak >= MAX_CLOSED_STREAK) return Optional.empty();
            if (check(d, config, memo)) return Optional.of(d);
        }
        return Optional.empty();
    }

    // 목표일 이후(포함) 기간 내 첫 개장일, 없으면 목표일 이전 기간 내 마지막 개장일, 둘 다 없으면 null
    private static LocalDate resolve(LocalDate target, LocalDate periodStart, LocalDate periodEnd, Predicate<LocalDate> isOpen) {
        for (LocalDate d = target; !d.isAfter(periodEnd); d = d.plusDays(1)) {
            if (isOpen.test(d)) return d;
        }
        for (LocalDate d = target.minusDays(1); !d.isBefore(periodStart); d = d.minusDays(1)) {
            if (isOpen.test(d)) return d;
        }
        return null;
    }
}
