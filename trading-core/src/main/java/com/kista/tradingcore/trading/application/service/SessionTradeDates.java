package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.TimeZones;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;
import com.kista.tradingcore.marketcalendar.domain.model.MarketSessionTimes;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

// KST 거래일 전환은 해당 날짜의 실제 마감 배치 시각을 따른다
final class SessionTradeDates {

    private static final int MAX_OPEN_LOOKAHEAD_DAYS = 10; // 주말+연휴 최장 휴장(4~6일)보다 넉넉한 탐색 상한

    private SessionTradeDates() {}

    static LocalDate nextTradeDate(ZonedDateTime now, MarketCalendarPort calendar) {
        LocalDate today = now.withZoneSameInstant(TimeZones.KST).toLocalDate();
        return MarketSessionTimes.nextTradeDate(now, calendar.findSession(today));
    }

    // date부터 첫 개장 거래일(KST) — 연도 캘린더 미적재면 isMarketOpen이 계속 false라 범위를 넘으면 empty
    static Optional<LocalDate> firstOpenOnOrAfter(LocalDate date, MarketCalendarPort calendar) {
        for (int i = 0; i < MAX_OPEN_LOOKAHEAD_DAYS; i++) {
            LocalDate candidate = date.plusDays(i);
            if (calendar.isMarketOpen(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    // 취소·인출·보관 점검 대상 날짜 — 캘린더 없이 오늘·내일(KST)을 모두 본다. 다음 거래일은 오늘 또는 내일이라 어느 쪽이든 포함하고,
    // 경계 전에는 내일 날짜 주문이 없어 조회만 늘 뿐이다. 정지·삭제·탈퇴가 캘린더 미적재로 막히지 않게 fail-closed인 nextTradeDate를 거치지 않는다
    static List<LocalDate> tradeDateCandidates(ZonedDateTime now) {
        LocalDate today = now.withZoneSameInstant(TimeZones.KST).toLocalDate();
        return List.of(today, today.plusDays(1));
    }
}