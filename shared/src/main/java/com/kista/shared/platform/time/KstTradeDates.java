package com.kista.shared.platform.time;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

// KST 거래일 계산 — 거래일 경계는 마감 배치 cron 발화(TradingCloseScheduler 04:30 KST)와 동일 임계값.
// US 거래일 변환은 UsTradeDates(어댑터 전용)와 무관하다.
public final class KstTradeDates {

    // 매매일 경계 기준 시각 — 04:30 이후면 당일 배치는 이미 계획 완료 → 다음 세션(익일)이 preview/수동 실행 대상
    public static final LocalTime SCHEDULER_RUN_TIME = LocalTime.of(4, 30);

    // 주어진 KST 시각 기준 거래일
    public static LocalDate nextTradeDate(ZonedDateTime nowKst) {
        return nextTradeDateAt(nowKst.toLocalDate(), nowKst.toLocalTime());
    }

    // 시각 주입식 판단 — nextTradeDate 공용
    public static LocalDate nextTradeDateAt(LocalDate today, LocalTime now) {
        return now.isBefore(SCHEDULER_RUN_TIME) ? today : today.plusDays(1);
    }

    private KstTradeDates() {}
}
