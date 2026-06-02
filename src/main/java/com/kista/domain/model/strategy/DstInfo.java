package com.kista.domain.model.strategy;

import java.time.Duration;
import java.time.Instant;
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
        RESERVATION, // 10:00~22:30(DST)/23:30(비DST) — KIS 예약주문(LOC) 사용
        DIRECT,      // 22:30/23:30~05:00/06:00 — 일반 LOC 주문 사용
        BLOCKED      // 05:00/06:00~10:00 — 주문 불가 (예약 수신 전, 장 마감 후)
    }

    // 현재 KST 시각 기준 주문 가능 시간대 판단
    public MarketSession currentSession() {
        LocalTime t = LocalTime.now(KST);
        // DST: 장마감 05:00, 장시작 22:30 / 비DST: 장마감 06:00, 장시작 23:30
        LocalTime marketClose = isDst ? LocalTime.of(5, 0) : LocalTime.of(6, 0);
        LocalTime marketOpen  = isDst ? LocalTime.of(22, 30) : LocalTime.of(23, 30);
        // blocked: [marketClose, 10:00)
        if (!t.isBefore(marketClose) && t.isBefore(LocalTime.of(10, 0))) return MarketSession.BLOCKED;
        // direct: [00:00, marketClose) OR [marketOpen, 24:00)
        if (t.isBefore(marketClose) || !t.isBefore(marketOpen)) return MarketSession.DIRECT;
        // reservation: [10:00, marketOpen)
        return MarketSession.RESERVATION;
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

    // 수동 실행용: orderAt을 과거로 설정해 waitUntilOrderTime() 스킵, postClose는 정상 계산
    public static DstInfo immediate() {
        ZonedDateTime now = ZonedDateTime.now(KST);
        boolean isDst = NY.getRules().isDaylightSavings(now.toInstant());
        LocalTime postTime = isDst ? LocalTime.of(5, 10) : LocalTime.of(6, 10);
        Instant postClose = now.toLocalDate().atTime(postTime).atZone(KST).toInstant();
        return new DstInfo(isDst, Instant.now().minusMillis(1), postClose);
    }
}
