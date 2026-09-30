package com.kista.marketcalendar.domain.model;

import com.kista.sharedkernel.MarketSession;
import com.kista.sharedkernel.TimeZones;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

// 현재 미국 시장 세션(수동 실행 가능 여부) 계산 — trading.domain.model.DstInfo.currentSession()/isDst()의
// 서브셋을 자체 소유로 복제(모듈 경계상 공유 불가, market↔trading 순환 방지 — broker의 PriceSnapshot과
// 동일 패턴). DST 판정·시각 상수는 DstInfo와 반드시 동기화 유지 — 자동 동기화 장치 없음, 사람이 양쪽 다 고쳐야 함.
public record MarketSessionSnapshot(boolean isDst, MarketSession session) {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static LocalTime marketCloseTime(boolean isDst)    { return isDst ? LocalTime.of(5, 0)  : LocalTime.of(6, 0); }
    private static LocalTime premarketStartTime(boolean isDst) { return isDst ? LocalTime.of(17, 0) : LocalTime.of(18, 0); }

    public static MarketSessionSnapshot now() {
        return at(ZonedDateTime.now(TimeZones.KST));
    }

    // 시각 주입식 판단 — 테스트 및 now() 공용
    static MarketSessionSnapshot at(ZonedDateTime nowKst) {
        boolean isDst = NY.getRules().isDaylightSavings(nowKst.toInstant());
        DayOfWeek day = nowKst.getDayOfWeek();
        LocalTime time = nowKst.toLocalTime();
        MarketSession session;
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            session = MarketSession.BLOCKED;
        } else if (!time.isBefore(marketCloseTime(isDst)) && time.isBefore(premarketStartTime(isDst))) {
            session = MarketSession.BLOCKED;
        } else {
            session = MarketSession.DIRECT;
        }
        return new MarketSessionSnapshot(isDst, session);
    }
}
