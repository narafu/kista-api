package com.kista.tradingcore.marketcalendar.domain.model;

import com.kista.shared.kernel.TimeZones;
import com.kista.shared.platform.time.KstTradeDates;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Optional;

public record MarketSessionTimes(Instant openAt, Instant closeAt) {

    private static final Duration TRADE_DATE_SWITCH_MARGIN = Duration.ofMinutes(30); // 마감 배치가 그날 주문을 넘겨받는 여유

    // KST 거래일 전환은 오늘 세션 실제 마감 30분 전 — 휴장일(세션 없음)은 04:30 KST 경계로 폴백
    public static LocalDate nextTradeDate(ZonedDateTime now, Optional<MarketSessionTimes> todaySession) {
        ZonedDateTime nowKst = now.withZoneSameInstant(TimeZones.KST);
        LocalDate today = nowKst.toLocalDate();
        return todaySession
                .map(session -> nowKst.toInstant().isBefore(session.closeAt().minus(TRADE_DATE_SWITCH_MARGIN))
                        ? today : today.plusDays(1))
                .orElseGet(() -> KstTradeDates.nextTradeDate(nowKst));
    }
}
