package com.kista.tradingcore.trading.application.service;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.time.KstTradeDates;
import com.kista.tradingcore.marketcalendar.application.port.output.MarketCalendarPort;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

// KST 거래일 전환은 해당 날짜의 실제 마감 배치 시각을 따른다
final class SessionTradeDates {

    private SessionTradeDates() {}

    static LocalDate nextTradeDate(ZonedDateTime now, MarketCalendarPort calendar) {
        ZonedDateTime nowKst = now.withZoneSameInstant(TimeZones.KST);
        LocalDate today = nowKst.toLocalDate();
        return calendar.findSession(today)
                .map(session -> nowKst.toInstant().isBefore(session.closeAt().minus(Duration.ofMinutes(30)))
                        ? today : today.plusDays(1))
                .orElseGet(() -> KstTradeDates.nextTradeDate(nowKst));
    }

    // 마감 전 주문과 다음 세션에 예약된 주문을 함께 보호한다
    static List<LocalDate> tradeDateCandidates(ZonedDateTime now, MarketCalendarPort calendar) {
        LocalDate today = now.withZoneSameInstant(TimeZones.KST).toLocalDate();
        return Stream.of(today, nextTradeDate(now, calendar)).distinct().toList();
    }
}