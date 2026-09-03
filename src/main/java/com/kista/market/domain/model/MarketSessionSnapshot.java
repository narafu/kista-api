package com.kista.market.domain.model;

import com.kista.common.TimeZones;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

// 현재 미국 시장 세션(수동 실행 가능 여부) 계산 — trading.domain.model.DstInfo.currentSession()/isDst()의
// 서브셋을 자체 소유로 복제(모듈 경계상 공유 불가, market↔trading 순환 방지 — broker의 Direction/OrderType과
// 동일 패턴). DST 판정·시각 상수는 DstInfo와 반드시 동기화 유지 — 자동 동기화 장치 없음, 사람이 양쪽 다 고쳐야 함.
public record MarketSessionSnapshot(boolean isDst, MarketSession session) {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // 수동 실행 시 주문 가능 시간대 (trading.DstInfo.MarketSession과 값 집합 동일)
    public enum MarketSession {
        DIRECT,  // 프리마켓+정규장: 주문 가능 (DST: 17:00~05:00, 비DST: 18:00~06:00)
        BLOCKED  // 장마감 후~프리마켓 전: 주문 불가 (DST: 05:00~17:00, 비DST: 06:00~18:00)
    }

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
