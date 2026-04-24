package com.kista.domain.model;

import java.time.Duration;
import java.time.Instant;

public record DstInfo(boolean isDst, Instant locDeadline, Instant postClose) {

    public Duration waitUntilLocDeadline() {
        return Duration.between(Instant.now(), locDeadline);
    }

    public Duration waitUntilPostClose() {
        return Duration.between(Instant.now(), postClose);
    }
}
