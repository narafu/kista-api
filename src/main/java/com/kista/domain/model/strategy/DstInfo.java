package com.kista.domain.model.strategy;

import com.kista.common.TimeZones;

import java.time.*;

public record DstInfo(
        boolean isDst,      // 미국 뉴욕 기준 일광절약시간(DST) 적용 여부
        Instant orderAt,    // 주문 시각 (KST): DST=04:30, 비DST=05:30
        Instant postClose,  // 시장 마감 후 체결 확인 시각 (KST): DST=05:10, 비DST=06:10
        Instant marketOpen  // 미 정규장 개장 시각 (KST): DST=22:30, 비DST=23:30
) {

    // 수동 실행 시 주문 가능 시간대
    public enum MarketSession {
        DIRECT,  // 프리마켓+정규장: 주문 가능 (DST: 17:00~05:00, 비DST: 18:00~06:00)
        BLOCKED  // 장마감 후~프리마켓 전: 주문 불가 (DST: 05:00~17:00, 비DST: 06:00~18:00)
    }

    // 현재 KST 기준 주문 가능 시간대 판단 — 주말은 요일 무관 BLOCKED
    public MarketSession currentSession() {
        DayOfWeek day = LocalDate.now(KST).getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return MarketSession.BLOCKED;
        LocalTime t = LocalTime.now(KST);
        // DST: 장마감 05:00, 프리마켓시작 17:00 / 비DST: 장마감 06:00, 프리마켓시작 18:00
        LocalTime marketClose    = isDst ? LocalTime.of(5, 0)  : LocalTime.of(6, 0);
        LocalTime premarketStart = isDst ? LocalTime.of(17, 0) : LocalTime.of(18, 0);
        // blocked: [marketClose, premarketStart)
        if (!t.isBefore(marketClose) && t.isBefore(premarketStart)) return MarketSession.BLOCKED;
        return MarketSession.DIRECT;
    }

    // BLOCKED 시간대 범위 설명 (KST) — 수동 실행 거부 메시지용
    public String blockedRangeDescription() {
        return isDst ? "05:00~17:00" : "06:00~18:00";
    }

    private static final ZoneId NY  = ZoneId.of("America/New_York");
    private static final ZoneId KST = TimeZones.KST;

    public Duration waitUntilOrderTime() {
        return Duration.between(Instant.now(), orderAt);
    }

    public Duration waitUntilPostClose() {
        return Duration.between(Instant.now(), postClose);
    }

    public Duration waitUntilMarketOpen() {
        return Duration.between(Instant.now(), marketOpen);
    }

    public static DstInfo calculate() {
        return calculate(ZonedDateTime.now(KST));
    }

    static DstInfo calculate(ZonedDateTime nowKst) {
        boolean isDst = NY.getRules().isDaylightSavings(nowKst.toInstant());
        LocalTime locTime  = isDst ? LocalTime.of(4, 30) : LocalTime.of(5, 30);
        LocalTime postTime = isDst ? LocalTime.of(5, 10) : LocalTime.of(6, 10);
        LocalTime openTime = isDst ? LocalTime.of(22, 30) : LocalTime.of(23, 30); // NY 09:30 = KST DST 22:30 / 비DST 23:30
        Instant orderAt    = nowKst.toLocalDate().atTime(locTime).atZone(KST).toInstant();
        Instant postClose  = nowKst.toLocalDate().atTime(postTime).atZone(KST).toInstant();
        Instant marketOpen = nowKst.toLocalDate().atTime(openTime).atZone(KST).toInstant();
        return new DstInfo(isDst, orderAt, postClose, marketOpen);
    }

    // 스케쥴러는 KST 04:00 실행 — 04:00 이후면 당일이 US 거래일이므로 오늘, 04:00 전이면 아직 전날
    private static final LocalTime SCHEDULER_RUN_TIME = LocalTime.of(4, 0);

    // preview/수동 실행에서 "오늘 매매 기준 날짜" 산출 — 스케쥴러와 동일 로직 SSOT
    public static LocalDate nextTradeDate() {
        return LocalTime.now(KST).isBefore(SCHEDULER_RUN_TIME)
                ? LocalDate.now(KST)
                : LocalDate.now(KST).plusDays(1);
    }

    // 개장 스케쥴러 수동 트리거용: marketOpen을 과거로 설정해 waitUntilMarketOpen() 스킵
    public static DstInfo immediateOpen() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean isDst = NY.getRules().isDaylightSavings(now.toInstant());
        LocalTime locTime  = isDst ? LocalTime.of(4, 30) : LocalTime.of(5, 30);
        LocalTime postTime = isDst ? LocalTime.of(5, 10) : LocalTime.of(6, 10);
        LocalDate date = now.toLocalDate();
        Instant orderAt   = date.atTime(locTime).atZone(KST).toInstant();
        Instant postClose = date.atTime(postTime).atZone(KST).toInstant();
        return new DstInfo(isDst, orderAt, postClose, Instant.now().minusMillis(1));
    }

    // 마감 스케쥴러 수동 트리거용: orderAt·postClose를 과거로 설정해 모든 대기 스킵
    public static DstInfo immediateClose() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean isDst = NY.getRules().isDaylightSavings(now.toInstant());
        LocalTime openTime = isDst ? LocalTime.of(22, 30) : LocalTime.of(23, 30);
        Instant past = Instant.now().minusMillis(1);
        Instant marketOpen = now.toLocalDate().atTime(openTime).atZone(KST).toInstant();
        return new DstInfo(isDst, past, past, marketOpen);
    }

    // 수동 실행용: orderAt을 과거로 설정해 waitUntilOrderTime() 스킵, postClose/marketOpen은 정상 계산
    public static DstInfo immediate() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean isDst = NY.getRules().isDaylightSavings(now.toInstant());
        LocalTime postTime = isDst ? LocalTime.of(5, 10) : LocalTime.of(6, 10);
        LocalTime openTime = isDst ? LocalTime.of(22, 30) : LocalTime.of(23, 30);
        // postTime이 이미 지났으면 내일 날짜 사용 (같은 날 05:10 KST 이후 호출 시 과거 Instant 방지)
        LocalDate targetDate = now.toLocalTime().isBefore(postTime)
                ? now.toLocalDate()
                : now.toLocalDate().plusDays(1);
        Instant postClose  = targetDate.atTime(postTime).atZone(KST).toInstant();
        Instant marketOpen = targetDate.atTime(openTime).atZone(KST).toInstant();
        return new DstInfo(isDst, Instant.now().minusMillis(1), postClose, marketOpen);
    }
}
