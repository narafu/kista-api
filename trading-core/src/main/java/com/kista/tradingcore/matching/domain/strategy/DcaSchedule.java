package com.kista.tradingcore.matching.domain.strategy;

import com.kista.tradingcore.matching.domain.model.DcaConfig;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Optional;
import java.util.function.Predicate;

// DCA 실행일 판정 — US 세션일 기준 순수 계산. KST↔US 변환은 호출부(MarketCalendarPort)가 담당
public final class DcaSchedule {

    private static final int SEARCH_LIMIT_DAYS = 400; // 다음 실행일 탐색 상한 — 휴장 데이터 미존재 연도 대비

    private DcaSchedule() {}

    // usSession이 이 설정의 실행일인지 — 휴장일은 항상 false
    public static boolean isExecutionDay(LocalDate usSession, DcaConfig config, Predicate<LocalDate> isOpen) {
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
    public static Optional<LocalDate> nextExecutionDay(LocalDate fromUsSession, DcaConfig config, Predicate<LocalDate> isOpen) {
        for (int i = 0; i < SEARCH_LIMIT_DAYS; i++) {
            LocalDate d = fromUsSession.plusDays(i);
            if (isExecutionDay(d, config, isOpen)) return Optional.of(d);
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
