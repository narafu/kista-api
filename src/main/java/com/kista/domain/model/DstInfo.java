package com.kista.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

public record DstInfo(boolean isDst, Instant locDeadline, Instant postClose) {

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
