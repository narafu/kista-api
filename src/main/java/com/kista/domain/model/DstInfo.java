package com.kista.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public record DstInfo(
        boolean isDst,      // 미국 뉴욕 기준 일광절약시간(DST) 적용 여부
        Instant locDeadline, // LOC 주문 마감 시각 (KST): DST=04:30, 비DST=05:30
        Instant postClose   // 시장 마감 후 체결 확인 시각 (KST): DST=05:10, 비DST=06:10
) {

    private static final ZoneId NY  = ZoneId.of("America/New_York");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public Duration waitUntilLocDeadline() {
        return Duration.between(Instant.now(), locDeadline);
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
        Instant locDeadline = nowKst.toLocalDate().atTime(locTime).atZone(KST).toInstant();
        Instant postClose   = nowKst.toLocalDate().atTime(postTime).atZone(KST).toInstant();
        return new DstInfo(isDst, locDeadline, postClose);
    }
}
