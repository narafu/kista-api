package com.kista.domain.model.strategy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public record DstInfo(
        boolean isDst,    // 미국 뉴욕 기준 일광절약시간(DST) 적용 여부
        Instant orderAt,  // 주문 시각 (KST): DST=04:30, 비DST=05:30
        Instant postClose // 시장 마감 후 체결 확인 시각 (KST): DST=05:10, 비DST=06:10
) {

    // 수동 실행 시 주문 가능 시간대
    public enum MarketSession {
        DIRECT,  // 프리마켓+정규장: 주문 가능 (DST: 17:00~05:00, 비DST: 18:00~06:00)
        BLOCKED  // 장마감 후~프리마켓 전: 주문 불가 (DST: 05:00~17:00, 비DST: 06:00~18:00)
    }

    // 현재 KST 시각 기준 주문 가능 시간대 판단
    public MarketSession currentSession() {
        LocalTime t = LocalTime.now(KST);
        // DST: 장마감 05:00, 프리마켓시작 17:00 / 비DST: 장마감 06:00, 프리마켓시작 18:00
        LocalTime marketClose    = isDst ? LocalTime.of(5, 0)  : LocalTime.of(6, 0);
        LocalTime premarketStart = isDst ? LocalTime.of(17, 0) : LocalTime.of(18, 0);
        // blocked: [marketClose, premarketStart)
        if (!t.isBefore(marketClose) && t.isBefore(premarketStart)) return MarketSession.BLOCKED;
        return MarketSession.DIRECT;
    }

    private static final ZoneId NY  = ZoneId.of("America/New_York");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public Duration waitUntilOrderTime() {
        return Duration.between(Instant.now(), orderAt);
    }

    public Duration waitUntilPostClose() {
        return Duration.between(Instant.now(), postClose);
    }

    public static DstInfo calculate() {
        return calculate(ZonedDateTime.now(KST));
    }

    static DstInfo calculate(ZonedDateTime nowKst) {
        boolean isDst = NY.getRules().isDaylightSavings(nowKst.toInstant());
        LocalTime locTime  = isDst ? LocalTime.of(4, 30) : LocalTime.of(5, 30);
        LocalTime postTime = isDst ? LocalTime.of(5, 10) : LocalTime.of(6, 10);
        Instant orderAt   = nowKst.toLocalDate().atTime(locTime).atZone(KST).toInstant();
        Instant postClose = nowKst.toLocalDate().atTime(postTime).atZone(KST).toInstant();
        return new DstInfo(isDst, orderAt, postClose);
    }

    // 스케줄러는 KST 04:00 실행 — 04:00 이후면 당일이 US 거래일이므로 오늘, 04:00 전이면 아직 전날
    private static final LocalTime SCHEDULER_RUN_TIME = LocalTime.of(4, 0);

    // preview/수동 실행에서 "오늘 매매 기준 날짜" 산출 — 스케줄러와 동일 로직 SSOT
    public static LocalDate nextTradeDate() {
        return LocalTime.now().isBefore(SCHEDULER_RUN_TIME)
                ? LocalDate.now()
                : LocalDate.now().plusDays(1);
    }

    // 수동 실행용: orderAt을 과거로 설정해 waitUntilOrderTime() 스킵, postClose는 정상 계산
    public static DstInfo immediate() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean isDst = NY.getRules().isDaylightSavings(now.toInstant());
        LocalTime postTime = isDst ? LocalTime.of(5, 10) : LocalTime.of(6, 10);
        // postTime이 이미 지났으면 내일 날짜 사용 (같은 날 05:10 KST 이후 호출 시 과거 Instant 방지)
        LocalDate targetDate = now.toLocalTime().isBefore(postTime)
                ? now.toLocalDate()
                : now.toLocalDate().plusDays(1);
        Instant postClose = targetDate.atTime(postTime).atZone(KST).toInstant();
        return new DstInfo(isDst, Instant.now().minusMillis(1), postClose);
    }
}
